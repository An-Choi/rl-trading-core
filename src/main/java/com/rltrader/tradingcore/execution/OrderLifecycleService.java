package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.config.OrderProperties;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisOrderAck;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.kis.KisTransportException;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * spec §2 커밋 경계의 물리적 구현.
 * - 중간 전이: OrderFinalizationService.transition() — 개별 tx 즉시 커밋
 * - terminal: OrderFinalizationService.finalizeChain() — 종결 전이 + 회계 + 체인 다음 상태 한 tx.
 *   다른 어디서도 terminal 금지
 * - 회계 확정: 항상 REST 확정 조회 + 예수금 (spec §3 분리 원칙). 확정 조회 실패 →
 *   ChainIncompleteException (주문은 비종결로 남아 §7 복구 대상)
 */
@Component
public class OrderLifecycleService {

    private final BrokerPort broker;
    private final OrderRepository orders;
    private final OrderFinalizationService finalization;
    private final OrderProperties props;
    private final Clock clock;

    public OrderLifecycleService(BrokerPort broker, OrderRepository orders,
            OrderFinalizationService finalization, OrderProperties props, Clock clock) {
        this.broker = broker;
        this.orders = orders;
        this.finalization = finalization;
        this.props = props;
        this.clock = clock;
    }

    public ChainOutcome runChain(long decisionId, OrderPlan plan, ZonedDateTime barTs,
            PortfolioState before, ArtifactContext ctx) {
        OrderEntity order = orders.findByDecisionIdAndStatus(decisionId, OrderStatus.NEW)
                .orElseThrow(() -> new IllegalStateException("no NEW intent for decision " + decisionId));
        // 체인 누계 3분리 (prior/current/total) — 절대 하나의 변수로 합치지 않는다:
        //   priorAccountedQty  = DB 복원(이전 attempt들의 accounted 합) — before 스냅숏에 이미 반영됨
        //   currentAttemptQty  = 이번 attempt의 REST 확정값
        //   decisionTotalQty   = prior + current
        // units 조건 판정은 total 기준, shares/cash 증감은 **current만** 적용(prior를 다시 더하면
        // 이중 반영), ChainOutcome 누계는 total. notional도 동일 3분리.
        // prior는 매 attempt 시작 시 DB 재조회한다(Task 9 루프) — 직전 attempt의 finalization이
        // accounted를 커밋했으므로, 인프로세스 연속 attempt와 재시작 재개가 같은 코드가 된다.
        final long priorAccountedQty = orders.sumAccountedFilledQtyByDecisionId(decisionId);
        final BigDecimal priorNotional = orders.sumAccountedFillNotionalByDecisionId(decisionId);

        // — 제출 (Task 8 범위: 성공 ack 경로만. 거부·모호·재시도는 Task 9·10이 이 지점을 확장)
        KisOrderAck ack = submitAttempt(order);
        finalization.transition(order, OrderStatus.SUBMITTED, Map.of());
        order.setKisOrderNo(ack.kisOrderNo());
        order.setSubmittedAt(OffsetDateTime.now(clock));
        finalization.transition(order, OrderStatus.ACKED, Map.of("kisOrderNo", ack.kisOrderNo()));

        // — 체결 폴링: canonical filled_qty 단조 갱신, terminal 후보 판정만 (terminal 커밋 금지)
        pollUntilTerminalCandidate(order, deadline());

        // — REST 확정 조회 + 예수금 → finalization (실패 시 비종결 유지)
        KisOrderSnapshot confirmed = confirmOrThrow(order);
        KisBalance balance = balanceOrThrow();
        long currentAttemptQty = confirmed.cumulativeFilledQty();
        BigDecimal currentNotional = confirmed.cumulativeAvgPrice()
                .multiply(BigDecimal.valueOf(currentAttemptQty));
        long decisionTotalQty = priorAccountedQty + currentAttemptQty;
        // applyFill은 current만 shares에 반영, units 판정은 decisionTotalQty > 0 기준
        PortfolioState after = applyFill(before, order, barTs, currentAttemptQty, decisionTotalQty, balance);
        finalization.finalizeChain(order, OrderStatus.FILLED, confirmed, after, ChainNext.markCompleted(),
                ctx, TransitionMode.NORMAL);
        return new ChainOutcome(after, priorNotional.add(currentNotional), decisionTotalQty, false);
    }

    /** 확장 지점(Task 9·10): 거부·모호·전송 실패 재시도가 이 메서드를 감싼다. */
    KisOrderAck submitAttempt(OrderEntity order) {
        return broker.submitMarketOrder(order.getSymbol(), order.getSide(), order.getQty());
    }

    /**
     * 데드라인은 제출(또는 재개) 시점 기준 — deadline = clock.instant() + fillTimeoutMs.
     * barTs 기준이면 지연·재개 시 이미 지난 시각이 된다. 15:20 특례(closingAuctionDeadline)는
     * Task 10이 우선시킨다. filled_qty는 max(현재, 보고값)로만 갱신(단조) — 증가 시
     * PARTIALLY_FILLED 전이를 개별 커밋한다. 단, terminal 후보로 판정되는 호출 자체는 전이를
     * 커밋하지 않는다 — 그 갱신은 finalizeChain의 종결 이벤트가 REST 확정값으로 대신 기록한다
     * (동일 filled_qty를 중간 전이·종결 전이 두 이벤트로 중복 기록하지 않기 위함).
     * brokerTerminal || filled==qty → 반환. 데드라인 도달 → 반환(Task 9가 취소 처리).
     * 매 폴링 사이 항상 fillPollIntervalMs 대기 — 스냅숏 변화 여부와 무관(API 남용 방지).
     * 테스트는 interval=0으로 설정해 대기를 무력화한다.
     */
    KisOrderSnapshot pollUntilTerminalCandidate(OrderEntity order, Instant deadline) {
        while (true) {
            KisOrderSnapshot snap = broker.orderSnapshot(order.getKisOrderNo(), order.getTradingDate())
                    .orElse(null);
            if (snap != null) {
                boolean terminalCandidate = snap.brokerTerminal() || snap.cumulativeFilledQty() >= order.getQty();
                long updated = Math.max(order.getFilledQty(), snap.cumulativeFilledQty());
                if (terminalCandidate) {
                    if (updated != order.getFilledQty()) {
                        // filled_qty만 커밋(단조 갱신) — 상태 전이는 finalizeChain의 종결
                        // 이벤트가 전담(중복 이벤트 방지). REST 확정이 이후 실패해도 이 값은
                        // 이미 DB에 남는다 (비종결 유지 시에도 "수량 진급은 반영" 규칙).
                        finalization.progressFilledQty(order, updated);
                    }
                    return snap;
                }
                if (updated != order.getFilledQty()) {
                    order.setFilledQty(updated);
                    finalization.transition(order, OrderStatus.PARTIALLY_FILLED, Map.of("filledQty", updated));
                }
            }
            if (!clock.instant().isBefore(deadline)) {
                return snap;
            }
            sleep(props.fillPollIntervalMs());
        }
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during fill poll wait", e);
        }
    }

    private Instant deadline() {
        return clock.instant().plusMillis(props.fillTimeoutMs());
    }

    /** REST 확정 실패(전송 계층) → ChainIncompleteException 변환, 주문은 비종결 유지. */
    private KisOrderSnapshot confirmOrThrow(OrderEntity order) {
        try {
            return broker.orderSnapshot(order.getKisOrderNo(), order.getTradingDate())
                    .orElseThrow(() -> new ChainIncompleteException(
                            "order snapshot not found for confirmation: " + order.getKisOrderNo(), null));
        } catch (KisTransportException e) {
            throw new ChainIncompleteException("REST confirm failed for " + order.getKisOrderNo(), e);
        }
    }

    private KisBalance balanceOrThrow() {
        try {
            return broker.balance();
        } catch (KisTransportException e) {
            throw new ChainIncompleteException("balance confirm failed", e);
        }
    }

    /**
     * shares 증감은 currentAttemptQty만(prior는 before에 이미 반영 — 이중 반영 금지),
     * units 판정은 decisionTotalQty > 0 기준(체인 최종 finalization에서만 갱신 — Task 9),
     * cash = balance.cashBalance().doubleValue()(예수금 절대값 투영). entry_bar_ts는 1차
     * DryRunExecutor와 동일 규칙: BUY로 처음 진입할 때만 새 bar로 세팅, CLEAR는 초기화.
     * Phase 1 스코프(single symbol, "Unit" 용어) — 포지션은 전량 진입/전량 청산만 존재하므로
     * unit은 0 또는 1이다.
     */
    private PortfolioState applyFill(PortfolioState before, OrderEntity order, ZonedDateTime barTs,
            long currentAttemptQty, long decisionTotalQty, KisBalance balance) {
        boolean isSell = order.getSide() == OrderSide.SELL;
        double signedDelta = isSell ? -currentAttemptQty : currentAttemptQty;
        double newShares = before.shares() + signedDelta;
        if (decisionTotalQty <= 0) {
            return new PortfolioState(before.units(), newShares, balance.cashBalance().doubleValue(),
                    before.entryBarTs());
        }
        if (isSell) {
            return new PortfolioState(0, newShares, balance.cashBalance().doubleValue(), null);
        }
        ZonedDateTime entry = before.units() == 0 ? barTs : before.entryBarTs();
        return new PortfolioState(1, newShares, balance.cashBalance().doubleValue(), entry);
    }
}
