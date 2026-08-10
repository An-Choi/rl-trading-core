package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStateMachine;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.ExecutionEntity;
import com.rltrader.tradingcore.ledger.ExecutionRepository;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderEventEntity;
import com.rltrader.tradingcore.ledger.OrderEventRepository;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * spec §2 커밋 경계의 물리적 구현 — transition()/finalizeChain() 소유자.
 * OrderLifecycleService(정상 체인)와 ReconciliationService(대사, Task 11)가 공유한다
 * (순환 의존 차단, P1-3). terminal 전이는 finalizeChain만 커밋한다.
 *
 * <p>커밋 성공 직후 portfolio.apply(after 투영)까지 이 컴포넌트의 책임 — "커밋 후 메모리
 * 갱신" 규칙은 중간 finalization(부분 체결 attempt)도 포함해 항상 지켜진다.
 */
@Component
public class OrderFinalizationService {

    private final OrderRepository orders;
    private final OrderEventRepository events;
    private final ExecutionRepository executions;
    private final PortfolioSnapshotRepository snapshots;
    private final DecisionRepository decisions;
    private final PortfolioManager portfolio;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper mapper;

    public OrderFinalizationService(OrderRepository orders, OrderEventRepository events,
            ExecutionRepository executions, PortfolioSnapshotRepository snapshots,
            DecisionRepository decisions, PortfolioManager portfolio, TransactionTemplate tx,
            Clock clock, ObjectMapper strictMapper) {
        this.orders = orders;
        this.events = events;
        this.executions = executions;
        this.snapshots = snapshots;
        this.decisions = decisions;
        this.portfolio = portfolio;
        this.tx = tx;
        this.clock = clock;
        this.mapper = strictMapper;
    }

    /** 중간 전이 — 개별 tx. terminal 전이가 들어오면 버그이므로 예외. */
    public void transition(OrderEntity order, OrderStatus to, Map<String, Object> detail) {
        transition(order, to, detail, TransitionMode.NORMAL);
    }

    /**
     * 폴링 canonical filled_qty 단조 갱신 전용 — 상태 전이·이벤트 없이 개별 tx로 즉시 커밋한다.
     * terminal 후보로 판정되는 마지막 폴링 호출에서 쓰인다: 그 시점의 filled_qty 진급은 이후
     * REST 확정(confirmOrThrow)이 실패해도 이미 관찰된 값으로 DB에 남아야 하지만, 상태 전이
     * 이벤트까지 남기면 finalizeChain의 종결 이벤트와 중복 기록된다 — 그래서 이벤트는 finalize가
     * 전담하고 filled_qty만 여기서 먼저 커밋한다.
     */
    public void progressFilledQty(OrderEntity order, long filledQty) {
        tx.executeWithoutResult(s -> {
            order.setFilledQty(filledQty);
            orders.save(order);
        });
    }

    /** 복구·대사의 비종결 관찰 점프(NEW→ACKED 등)도 저장할 수 있어야 한다 — mode 오버로드. */
    public void transition(OrderEntity order, OrderStatus to, Map<String, Object> detail,
            TransitionMode mode) {
        if (to.isTerminal()) {
            throw new IllegalStateException("terminal transition outside finalization: " + to);
        }
        if (mode == TransitionMode.RECOVERY) {
            OrderStateMachine.assertRecoveryJump(order.getStatus(), to);
        } else {
            OrderStateMachine.assertTransition(order.getStatus(), to);
        }
        tx.executeWithoutResult(s -> {
            recordEvent(order, order.getStatus(), to, detail);
            order.setStatus(to);
            orders.save(order);
        });
    }

    /**
     * finalization tx — 종결 전이 + (체결 있으면) execution + snapshot + 체인 다음 상태 (spec §2).
     * TransitionMode.NORMAL은 정상 전이표, RECOVERY는 복구 점프표로 검증 — 복구가 NEW→FILLED를
     * 관찰해도 finalization이 거부하지 않게 두 검증을 여기서 연결한다.
     * 동기 거부 특례: NEW 상태에서 거부 응답 수신(= 송신은 확인됨) 시 이 tx가 NEW→SUBMITTED,
     * SUBMITTED→REJECTED 두 이벤트를 함께 기록한다 — NEW→REJECTED 직행을 전이표에 넣지 않고
     * 실제 있었던 순서를 기록하는 쪽을 택한다.
     */
    public void finalizeChain(OrderEntity order, OrderStatus terminal, KisOrderSnapshot confirmed,
            PortfolioState after, ChainNext next, ArtifactContext ctx, TransitionMode mode) {
        boolean syncReject = mode == TransitionMode.NORMAL
                && order.getStatus() == OrderStatus.NEW && terminal == OrderStatus.REJECTED;
        if (syncReject) {
            // 동기 거부 특례: NEW에서 거부 응답 수신 = 송신은 확인된 것 — 실제 순서
            // (NEW→SUBMITTED→REJECTED)를 두 이벤트로 기록. 전이표엔 NEW→REJECTED 직행이 없다.
            OrderStateMachine.assertTransition(OrderStatus.NEW, OrderStatus.SUBMITTED);
            OrderStateMachine.assertTransition(OrderStatus.SUBMITTED, OrderStatus.REJECTED);
        } else if (mode == TransitionMode.RECOVERY) {
            OrderStateMachine.assertRecoveryJump(order.getStatus(), terminal);
        } else {
            OrderStateMachine.assertTransition(order.getStatus(), terminal);
        }
        tx.executeWithoutResult(s -> {
            if (syncReject) {
                recordEvent(order, OrderStatus.NEW, OrderStatus.SUBMITTED,
                        Map.of("note", "sync-reject: submission confirmed by rejection response"));
                recordEvent(order, OrderStatus.SUBMITTED, terminal, Map.of());
            } else {
                recordEvent(order, order.getStatus(), terminal, Map.of());
            }
            order.setStatus(terminal);
            order.setTerminalAt(OffsetDateTime.now(clock));
            if (confirmed != null && confirmed.cumulativeFilledQty() > 0) {
                // zero-fill 종결(REJECTED·ABORTED·전량 미체결 CANCELLED)은 회계가 없다 —
                // execution 행을 만들지 않는다 (UNIQUE(order_id)는 회계 있는 종결 전용)
                order.setFilledQty(confirmed.cumulativeFilledQty());
                order.setAvgFillPrice(confirmed.cumulativeAvgPrice());
                order.setAccountedFilledQty(confirmed.cumulativeFilledQty());
                order.setAccountedFillNotional(confirmed.cumulativeAvgPrice()
                        .multiply(BigDecimal.valueOf(confirmed.cumulativeFilledQty())));
                saveExecution(order, after);
            }
            orders.save(order);
            saveSnapshot(order, after, ctx, next.liquidationPending(), mode);
            if (next.completed()) {
                var d = decisions.findById(order.getDecisionId()).orElseThrow();
                d.setExecutionStatus("COMPLETED");
                decisions.save(d);
            }
            if (next.nextAttempt() != null) {
                orders.save(next.nextAttempt());   // 후속 attempt NEW — 같은 tx (체인 원자성)
            }
        });
        portfolio.apply(after);   // 커밋 성공 직후에만 메모리 반영 (spec §2) — 중간 finalization도 포함
    }

    private void recordEvent(OrderEntity order, OrderStatus from, OrderStatus to,
            Map<String, Object> detail) {
        OrderEventEntity e = new OrderEventEntity();
        e.setOrderId(order.getId());
        e.setFromStatus(from.name());
        e.setToStatus(to.name());
        e.setDetail(detail == null || detail.isEmpty() ? null : mapper.writeValueAsString(detail));
        events.save(e);
    }

    private void saveExecution(OrderEntity order, PortfolioState after) {
        BigDecimal signedQty = BigDecimal.valueOf(order.getAccountedFilledQty());
        ExecutionEntity e = new ExecutionEntity();
        e.setDecisionId(order.getDecisionId());
        e.setOrderId(order.getId());
        e.setFillPrice(order.getAvgFillPrice().doubleValue());
        e.setSharesDelta(order.getSide() == OrderSide.SELL
                ? -signedQty.doubleValue() : signedQty.doubleValue());
        e.setUnitsAfter(after.units());
        e.setSharesAfter(after.shares());
        e.setCashAfter(after.cash());
        executions.save(e);
    }

    /**
     * 1차 persistCycle의 snapshot UPSERT 로직과 동일 + liquidationPending.
     * artifact provenance (Task 14 의존): NORMAL 모드는 ctx.artifactId()를 기록(1차 관례).
     * RECOVERY 모드는 기존 snapshot의 artifact_id를 보존하고, snapshot이 없어 신규 INSERT라면
     * **이 order가 속한 decision의 artifact_id**(order.getDecisionId() → DecisionRepository)를
     * 기록한다 — ctx는 현재 프로세스가 로드한 아티팩트일 뿐 복구 대상 decision의 아티팩트와
     * 다를 수 있으므로 RECOVERY 경로에서는 ctx를 쓰지 않는다.
     */
    private void saveSnapshot(OrderEntity order, PortfolioState after, ArtifactContext ctx,
            boolean liquidationPending, TransitionMode mode) {
        var existing = snapshots.findById(order.getSymbol());
        PortfolioSnapshotEntity s = existing.orElseGet(PortfolioSnapshotEntity::new);
        s.setSymbol(order.getSymbol());
        s.setUnits(after.units());
        s.setShares(after.shares());
        s.setCash(after.cash());
        s.setEntryBarTs(after.entryBarTs() != null ? after.entryBarTs().toOffsetDateTime() : null);
        if (mode == TransitionMode.RECOVERY) {
            String recoveryArtifactId = existing.isPresent() ? existing.get().getArtifactId()
                    : decisions.findById(order.getDecisionId()).orElseThrow().getArtifactId();
            s.setArtifactId(recoveryArtifactId);
        } else {
            s.setArtifactId(ctx.artifactId());
        }
        s.setUpdatedAt(OffsetDateTime.now(clock));
        s.setLiquidationPending(liquidationPending);
        snapshots.save(s);
    }
}
