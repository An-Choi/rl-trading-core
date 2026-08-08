package com.rltrader.tradingcore.reconcile;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.execution.ChainNext;
import com.rltrader.tradingcore.execution.OrderFinalizationService;
import com.rltrader.tradingcore.execution.TransitionMode;
import com.rltrader.tradingcore.halt.HaltService;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.kis.KisTransportException;
import com.rltrader.tradingcore.ledger.ExecutionAdjustmentEntity;
import com.rltrader.tradingcore.ledger.ExecutionAdjustmentRepository;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderEventEntity;
import com.rltrader.tradingcore.ledger.OrderEventRepository;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.scheduler.CycleAdmissionGate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 2레벨 대사(spec §6) — 주문 레벨(비종결 관찰 점프 + 지연 체결 정정) / 잔고 레벨(shares·cash).
 * OrderLifecycleService가 아니라 공유 컴포넌트(OrderFinalizationService)만 소비한다 — 순환 의존
 * 차단(P1-3, 브리프 Interfaces 블록). terminal 소유권은 finalization 경로가 유지하되, CANCELLED→
 * FILLED 지연 체결 정정만 spec §3이 명시한 단일 예외로 이 클래스가 직접 커밋한다
 * (correctCancelledToFilled — 아래 상세 주석).
 */
@Component
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Set<OrderStatus> NON_TERMINAL =
            EnumSet.complementOf(EnumSet.of(OrderStatus.FILLED, OrderStatus.CANCELLED,
                    OrderStatus.REJECTED, OrderStatus.ABORTED));
    private static final int SCALE = 4;

    private final BrokerPort broker;
    private final OrderRepository orders;
    private final OrderFinalizationService finalization;
    private final OrderEventRepository events;
    private final ExecutionAdjustmentRepository adjustments;
    private final PortfolioSnapshotRepository snapshots;
    private final PortfolioManager portfolio;
    private final HaltService halts;
    private final CycleAdmissionGate gate;
    private final ReconciliationRepository reconciliations;
    private final TradingProperties tradingProps;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ReconciliationService(BrokerPort broker, OrderRepository orders,
            OrderFinalizationService finalization, OrderEventRepository events,
            ExecutionAdjustmentRepository adjustments, PortfolioSnapshotRepository snapshots,
            PortfolioManager portfolio, HaltService halts, CycleAdmissionGate gate,
            ReconciliationRepository reconciliations, TradingProperties tradingProps,
            TransactionTemplate tx, Clock clock, ObjectMapper strictMapper,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.broker = broker;
        this.orders = orders;
        this.finalization = finalization;
        this.events = events;
        this.adjustments = adjustments;
        this.snapshots = snapshots;
        this.portfolio = portfolio;
        this.halts = halts;
        this.gate = gate;
        this.reconciliations = reconciliations;
        this.tradingProps = tradingProps;
        this.tx = tx;
        this.clock = clock;
        this.mapper = strictMapper;
        this.jdbc = jdbc;
    }

    /**
     * 진입점. PERIODIC만 CycleAdmissionGate를 탄다 — 실패 시 기록 없이 즉시 반환(브리프 계약).
     * POST_ORDER/STARTUP/RECONNECT는 호출자가 이미 단일 진행을 보장한다고 가정한다
     * (POST_ORDER는 사이클 완료 직후, 같은 스케줄러 tick 안에서 순차 호출 — 별도 게이트 불필요).
     */
    public void reconcile(Trigger trigger) {
        if (trigger == Trigger.PERIODIC) {
            if (!gate.tryAcquire()) {
                return;   // 사이클 진행 중 — 기록 없이 skip (브리프 계약)
            }
            try {
                runBothLevels(trigger);
            } finally {
                gate.release();
            }
        } else {
            runBothLevels(trigger);
        }
    }

    private void runBothLevels(Trigger trigger) {
        boolean adjustmentApplied = reconcileOrders(trigger);
        reconcileBalance(trigger, adjustmentApplied);
    }

    // ---------------------------------------------------------------- 주문 레벨

    /**
     * 비종결 주문마다 KIS 조회 → 관찰 점프로 확정, CANCELLED 주문마다 지연 체결 정정 여부 확인.
     * @return 이번 호출에서 adjustment를 하나라도 적용했는지 — 잔고 레벨의 "설명 가능" 판정에 쓰인다.
     */
    private boolean reconcileOrders(Trigger trigger) {
        List<Map<String, Object>> detail = new ArrayList<>();
        boolean anySync = false;

        for (OrderEntity order : orders.findByStatusIn(NON_TERMINAL)) {
            if (order.getKisOrderNo() == null) {
                continue;   // 아직 제출조차 확인 안 된 주문(NEW) — 조회 대상 아님
            }
            KisOrderSnapshot snap = fetchSnapshot(order);
            if (snap == null) {
                continue;
            }
            boolean filled = snap.cumulativeFilledQty() >= order.getQty()
                    || (snap.brokerTerminal() && !snap.cancelled());
            if (filled) {
                finalizeObservedFill(order, snap);
                detail.add(Map.of("orderId", order.getId(), "action", "FINALIZED_FILLED"));
                anySync = true;
            } else if (snap.brokerTerminal() && snap.cancelled()) {
                finalizeObservedCancel(order, snap);
                detail.add(Map.of("orderId", order.getId(), "action", "FINALIZED_CANCELLED"));
                anySync = true;
            } else {
                // 미체결 잔존 — 브로커에서 여전히 살아있는 주문. 취소 요청으로 수렴시킨다
                // (취소 확정은 이후 폴링/대사 회차가 관찰 점프로 마무리한다).
                requestCancel(order);
                detail.add(Map.of("orderId", order.getId(), "action", "CANCEL_REQUESTED"));
                anySync = true;
            }
        }

        boolean adjustmentApplied = reconcileCancelledAdjustments(detail);
        anySync = anySync || adjustmentApplied;

        record(trigger, "ORDER", anySync ? "SYNCED" : "CLEAN", Map.of("orders", detail));
        return adjustmentApplied;
    }

    private KisOrderSnapshot fetchSnapshot(OrderEntity order) {
        try {
            return broker.orderSnapshot(order.getKisOrderNo(), order.getTradingDate()).orElse(null);
        } catch (KisTransportException e) {
            log.warn("[RECON] snapshot fetch failed for order {} — skip this pass", order.getId(), e);
            return null;
        }
    }

    private void requestCancel(OrderEntity order) {
        try {
            broker.cancelOrder(order.getKisOrderNo(), order.getQty() - order.getFilledQty());
        } catch (KisTransportException e) {
            log.warn("[RECON] cancel request failed for order {} — retry next pass", order.getId(), e);
        }
    }

    /** 관찰 점프(FILLED) — 공유 finalization 경로, RECOVERY 모드(spec §6 Interfaces 블록). */
    private void finalizeObservedFill(OrderEntity order, KisOrderSnapshot snap) {
        KisBalance balance = balanceOrNull();
        if (balance == null) {
            return;   // 잔고 확정 실패 — 이번 회차는 건너뛰고 비종결 유지 (다음 대사가 재시도)
        }
        long delta = snap.cumulativeFilledQty() - order.getAccountedFilledQty();
        PortfolioState after = applyObservedFill(order, delta, balance);
        // RECOVERY 모드에서 ctx는 finalizeChain 내부에서 절대 역참조되지 않는다
        // (OrderFinalizationService.saveSnapshot의 RECOVERY 분기는 기존 snapshot의 artifact_id를
        // 보존하거나 decision의 artifact_id로 대체한다 — ctx.artifactId() 호출 경로는 NORMAL 전용).
        finalization.finalizeChain(order, OrderStatus.FILLED, snap, after,
                ChainNext.markCompleted(), null, TransitionMode.RECOVERY);
    }

    /**
     * 관찰 점프(CANCELLED, 잔량 있음) — brokerTerminal && cancelled.
     * 리뷰 수정 — SELL이고 잔량(qty − canonical filled)이 남아있으면 OrderLifecycleService의
     * 정상 취소 경로와 동일하게 ChainNext.liquidation()으로 이월해야 한다(spec §10 이월 규칙).
     * 무조건 markCompleted()로 커밋하면 decisions.execution_status가 COMPLETED로 확정되고
     * liquidationPending도 false로 남아, LiquidationService.resume()의 유일한 스캐너가 이 잔량을
     * 영구히 찾지 못한다 — "정상 경로와 동일 취급"이 대사 경로에서도 지켜져야 한다(spec §6).
     * BUY 잔량은 정상 경로도 재시도 상한 도달 시 add를 포기하고 completed로 끝내므로(관찰 시점에는
     * 재시도 여부를 판단할 attempt 컨텍스트가 없다) 대사는 안전하게 completed로 확정한다.
     */
    private void finalizeObservedCancel(OrderEntity order, KisOrderSnapshot snap) {
        KisBalance balance = balanceOrNull();
        if (balance == null) {
            return;
        }
        long delta = snap.cumulativeFilledQty() - order.getAccountedFilledQty();
        PortfolioState after = applyObservedFill(order, delta, balance);
        long remaining = order.getQty() - snap.cumulativeFilledQty();
        ChainNext next = order.getSide() == OrderSide.SELL && remaining > 0
                ? ChainNext.liquidation() : ChainNext.markCompleted();
        finalization.finalizeChain(order, OrderStatus.CANCELLED, snap, after,
                next, null, TransitionMode.RECOVERY);
    }

    private PortfolioState applyObservedFill(OrderEntity order, long deltaQty, KisBalance balance) {
        PortfolioState before = portfolio.current();
        if (deltaQty <= 0) {
            return new PortfolioState(before.units(), before.shares(),
                    balance.cashBalance().doubleValue(), before.entryBarTs());
        }
        double signedDelta = order.getSide() == OrderSide.SELL ? -deltaQty : deltaQty;
        return new PortfolioState(before.units(), before.shares() + signedDelta,
                balance.cashBalance().doubleValue(), before.entryBarTs());
    }

    private KisBalance balanceOrNull() {
        try {
            return broker.balance();
        } catch (KisTransportException e) {
            log.warn("[RECON] balance fetch failed — skip this order's finalization this pass", e);
            return null;
        }
    }

    /**
     * 지연 체결 정정 — CANCELLED인데 KIS 누적 체결이 accounted를 초과하는 경우(spec §3 명시 규칙).
     * delta > 0일 때만 lock 하에: adjustment INSERT + orders.accounted 갱신 + snapshot delta 반영,
     * 한 tx. 같은 스냅숏으로 재실행하면 delta==0이 되어 아무 것도 하지 않는다(멱등).
     */
    private boolean reconcileCancelledAdjustments(List<Map<String, Object>> detail) {
        boolean applied = false;
        for (OrderEntity cancelled : orders.findByStatusIn(List.of(OrderStatus.CANCELLED))) {
            if (cancelled.getKisOrderNo() == null) {
                continue;
            }
            KisOrderSnapshot snap = fetchSnapshot(cancelled);
            if (snap == null || snap.cumulativeAvgPrice() == null) {
                continue;
            }
            long canonicalQty = snap.cumulativeFilledQty();
            if (canonicalQty <= cancelled.getAccountedFilledQty()) {
                continue;   // delta <= 0 — 이미 반영됐거나 관찰값이 뒤처짐, 정정 대상 아님
            }
            BigDecimal toNotional = snap.cumulativeAvgPrice()
                    .multiply(BigDecimal.valueOf(canonicalQty))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            boolean didApply = applyAdjustmentTx(cancelled.getId(), canonicalQty, toNotional);
            if (didApply) {
                applied = true;
                detail.add(Map.of("orderId", cancelled.getId(), "action", "ADJUSTMENT"));
            }
        }
        return applied;
    }

    /**
     * lock 하 재확인 + adjustment INSERT + orders.accounted 갱신 + snapshot delta + (정정 후
     * filled==qty면) correctCancelledToFilled — 전부 한 tx. 커밋 성공 직후 메모리 갱신
     * (portfolio.apply)까지 이 메서드가 책임진다(spec §2 "커밋 후 메모리" 규칙, finalization과 동일 패턴).
     */
    private boolean applyAdjustmentTx(long orderId, long canonicalQty, BigDecimal toNotional) {
        record Result(boolean applied, PortfolioState after) {}
        Result result = tx.execute(status -> {
            OrderEntity locked = orders.findByIdForUpdate(orderId).orElseThrow();
            long delta = canonicalQty - locked.getAccountedFilledQty();
            if (delta <= 0) {
                return new Result(false, null);   // 동시 대사 회차가 이미 반영 — 멱등
            }
            BigDecimal fromNotional = locked.getAccountedFillNotional();
            BigDecimal notionalDelta = toNotional.subtract(fromNotional).setScale(SCALE, RoundingMode.HALF_UP);

            ExecutionAdjustmentEntity adj = new ExecutionAdjustmentEntity();
            adj.setOrderId(orderId);
            adj.setFromFilledQty(locked.getAccountedFilledQty());
            adj.setToFilledQty(canonicalQty);
            adj.setFromFillNotional(fromNotional);
            adj.setToFillNotional(toNotional);
            adj.setQtyDelta(delta);
            adj.setNotionalDelta(notionalDelta);
            adj.setReason("RECONCILE_LATE_FILL");
            adjustments.save(adj);

            locked.setAccountedFilledQty(canonicalQty);
            locked.setAccountedFillNotional(toNotional);
            locked.setFilledQty(Math.max(locked.getFilledQty(), canonicalQty));
            orders.save(locked);

            PortfolioState before = portfolio.current();
            double signedDelta = locked.getSide() == OrderSide.SELL ? -delta : delta;
            PortfolioState after = new PortfolioState(before.units(), before.shares() + signedDelta,
                    before.cash(), before.entryBarTs());

            PortfolioSnapshotEntity snapshotEntity = snapshots.findById(locked.getSymbol()).orElseThrow();
            snapshotEntity.setShares(after.shares());
            snapshotEntity.setUpdatedAt(OffsetDateTime.now(clock));
            snapshots.save(snapshotEntity);

            if (canonicalQty == locked.getQty()) {
                correctCancelledToFilled(locked);
            }
            return new Result(true, after);
        });
        if (result.applied()) {
            portfolio.apply(result.after());   // 커밋 성공 직후에만 메모리 반영 (spec §2)
        }
        return result.applied();
    }

    /**
     * spec §3 명시 규칙 — CANCELLED(terminal)→FILLED 정정. 상태머신 우회가 아니다: 일반 관찰
     * 점프(RECOVERY jump)는 "마지막 커밋 상태 → 관찰 상태" 전방 직행만 허용하고 terminal에서는
     * 출발할 수 없다(OrderStateMachine.RECOVERY 맵에 CANCELLED가 출발점으로 없음) — 그러나 지연
     * 체결 정정은 예외적으로 이미 종결된 CANCELLED 주문이 사실은 전량 체결이었음을 뒤늦게
     * 발견하는 경우이므로, 이 adjustment 경로에서만 직접 상태를 갱신한다(OrderStateMachine을
     * 거치지 않음 — 브리프가 명시적으로 지시한 별도 메서드). order_events에도 그 사실을 남긴다.
     * applyAdjustmentTx의 트랜잭션 안에서만 호출되어야 한다(별도 tx 아님 — 원자성 보장).
     */
    private void correctCancelledToFilled(OrderEntity order) {
        OrderEventEntity e = new OrderEventEntity();
        e.setOrderId(order.getId());
        e.setFromStatus(OrderStatus.CANCELLED.name());
        e.setToStatus(OrderStatus.FILLED.name());
        e.setDetail(mapper.writeValueAsString(Map.of(
                "note", "late-fill adjustment correction — spec §3 explicit exception, not a state machine bypass",
                "reason", "RECONCILE_LATE_FILL")));
        events.save(e);
        order.setStatus(OrderStatus.FILLED);
        order.setTerminalAt(OffsetDateTime.now(clock));
        orders.save(order);
    }

    // ---------------------------------------------------------------- 잔고 레벨

    /**
     * shares: 로컬(PortfolioManager) == accounting_ledger 합(±side) == KIS holdingQty.
     * cash: 로컬 cash vs KIS 예수금 — 불일치면 무조건(설명 가능성 무관) 절대값 SYNC(spec §6).
     * shares 불일치는 비종결 주문 존재 또는 이번 회차 adjustment 적용(explained)이면 SYNCED,
     * 그렇지 않으면 HaltService.raise("RECON_UNEXPLAINED", ...) + UNEXPLAINED 기록.
     */
    private void reconcileBalance(Trigger trigger, boolean adjustmentAppliedThisPass) {
        KisBalance kis;
        try {
            kis = broker.balance();
        } catch (KisTransportException e) {
            log.warn("[RECON] balance fetch failed — balance-level reconciliation skipped this pass", e);
            record(trigger, "BALANCE", "UNEXPLAINED", Map.of("reason", "balance fetch failed"));
            return;
        }

        PortfolioState local = portfolio.current();
        BigDecimal ledgerSum = ledgerShareSum();
        BigDecimal localShares = BigDecimal.valueOf(local.shares());
        BigDecimal kisShares = BigDecimal.valueOf(kis.holdingQty());

        boolean sharesClean = localShares.compareTo(ledgerSum) == 0 && localShares.compareTo(kisShares) == 0;
        String sharesResult;
        if (sharesClean) {
            sharesResult = "CLEAN";
        } else {
            boolean explainable = adjustmentAppliedThisPass || !orders.findByStatusIn(NON_TERMINAL).isEmpty();
            if (explainable) {
                sharesResult = "SYNCED";
            } else {
                sharesResult = "UNEXPLAINED";
                halts.raise("RECON_UNEXPLAINED", Map.of(
                        "localShares", localShares, "ledgerSum", ledgerSum, "kisHoldingQty", kisShares));
            }
        }

        BigDecimal localCash = BigDecimal.valueOf(local.cash());
        BigDecimal kisCash = kis.cashBalance();
        boolean cashClean = localCash.compareTo(kisCash) == 0;
        String cashResult = "CLEAN";
        if (!cashClean) {
            syncCashFromBalance(kisCash);
            cashResult = "SYNCED";
        }

        String overall = worst(sharesResult, cashResult);
        record(trigger, "BALANCE", overall, Map.of(
                "shares", Map.of("result", sharesResult, "local", localShares,
                        "ledger", ledgerSum, "kis", kisShares),
                "cash", Map.of("result", cashResult, "local", localCash, "kis", kisCash)));
    }

    /** cash 불일치 → 예수금 절대값으로 snapshot·메모리 동기화(단방향 로컬←KIS, spec §6). 한 tx. */
    private void syncCashFromBalance(BigDecimal kisCash) {
        PortfolioState after = tx.execute(status -> {
            PortfolioState before = portfolio.current();
            PortfolioState next = new PortfolioState(before.units(), before.shares(),
                    kisCash.doubleValue(), before.entryBarTs());
            PortfolioSnapshotEntity snapshotEntity = snapshots.findById(tradingProps.symbol())
                    .orElseThrow(() -> new IllegalStateException(
                            "no portfolio snapshot for " + tradingProps.symbol() + " — cannot sync cash"));
            snapshotEntity.setCash(next.cash());
            snapshotEntity.setUpdatedAt(OffsetDateTime.now(clock));
            snapshots.save(snapshotEntity);
            return next;
        });
        portfolio.apply(after);   // 커밋 성공 직후 메모리 갱신 (spec §2)
    }

    /** accounting_ledger는 order_id로만 연결된다 — 이 심볼의 orders에 조인해 합산(단일 심볼 전제, 미래 다심볼 대비). */
    private BigDecimal ledgerShareSum() {
        BigDecimal sum = jdbc.queryForObject("""
            SELECT COALESCE(SUM(l.qty_delta), 0) FROM accounting_ledger l
            JOIN orders o ON o.id = l.order_id WHERE o.symbol = ?""",
                BigDecimal.class, tradingProps.symbol());
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private String worst(String a, String b) {
        if ("UNEXPLAINED".equals(a) || "UNEXPLAINED".equals(b)) {
            return "UNEXPLAINED";
        }
        if ("SYNCED".equals(a) || "SYNCED".equals(b)) {
            return "SYNCED";
        }
        return "CLEAN";
    }

    private void record(Trigger trigger, String level, String result, Map<String, Object> detail) {
        ReconciliationEntity e = new ReconciliationEntity();
        e.setTriggerSource(trigger.name());
        e.setLevel(level);
        e.setResult(result);
        e.setDetail(mapper.writeValueAsString(detail));
        reconciliations.save(e);
    }
}
