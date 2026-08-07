package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.config.OrderProperties;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradingGrid;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisOrderAck;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.kis.KisTransportException;
import com.rltrader.tradingcore.ledger.AlertRecorder;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
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
    private final AlertRecorder alerts;

    public OrderLifecycleService(BrokerPort broker, OrderRepository orders,
            OrderFinalizationService finalization, OrderProperties props, Clock clock,
            AlertRecorder alerts) {
        this.broker = broker;
        this.orders = orders;
        this.finalization = finalization;
        this.props = props;
        this.clock = clock;
        this.alerts = alerts;
    }

    public ChainOutcome runChain(long decisionId, OrderPlan plan, ZonedDateTime barTs,
            PortfolioState before, ArtifactContext ctx) {
        OrderEntity order = orders.findByDecisionIdAndStatus(decisionId, OrderStatus.NEW)
                .orElseThrow(() -> new IllegalStateException("no NEW intent for decision " + decisionId));
        // attempt 루프 (Task 9): 각 반복 시작 시 prior를 DB에서 재조회한다 — 직전 attempt의
        // finalization이 accounted를 이미 커밋했으므로, 인프로세스 연속 attempt와 재시작 후
        // 재개가 동일 코드 경로가 된다 (in-process/restart 동형성).
        while (true) {
            // 체인 누계 3분리 (prior/current/total) — 절대 하나의 변수로 합치지 않는다:
            //   priorAccountedQty  = DB 복원(이전 attempt들의 accounted 합) — before 스냅숏에 이미 반영됨
            //   currentAttemptQty  = 이번 attempt의 REST 확정값
            //   decisionTotalQty   = prior + current
            // units 조건 판정은 total 기준, shares/cash 증감은 **current만** 적용(prior를 다시 더하면
            // 이중 반영), ChainOutcome 누계는 total. notional도 동일 3분리.
            final long priorAccountedQty = orders.sumAccountedFilledQtyByDecisionId(decisionId);
            final BigDecimal priorNotional = orders.sumAccountedFillNotionalByDecisionId(decisionId);

            // — 제출 (Task 8 범위: 성공 ack 경로만. 거부·모호는 Task 10이 이 지점을 확장)
            KisOrderAck ack = submitAttempt(order);
            finalization.transition(order, OrderStatus.SUBMITTED, Map.of());
            order.setKisOrderNo(ack.kisOrderNo());
            order.setSubmittedAt(OffsetDateTime.now(clock));
            finalization.transition(order, OrderStatus.ACKED, Map.of("kisOrderNo", ack.kisOrderNo()));

            // — 체결 폴링: canonical filled_qty 단조 갱신, terminal 후보 판정만 (terminal 커밋 금지)
            KisOrderSnapshot polled = pollUntilTerminalCandidate(order, deadline(barTs));

            boolean filledCandidate = polled != null
                    && (polled.cumulativeFilledQty() >= order.getQty() || (polled.brokerTerminal() && !polled.cancelled()));
            if (filledCandidate) {
                ChainOutcome outcome = confirmAndFinalizeFilled(order, barTs, before, ctx,
                        priorAccountedQty, priorNotional);
                if (outcome != null) {
                    return outcome;
                }
                // confirm이 결국 전량 미체결로 판명 — 아래 취소 경로로 폴백하지 않고
                // (이미 폴링이 terminalCandidate라 판단했으므로) 데드라인 도달로 취급한다.
            }

            // — 타임아웃(또는 terminalCandidate가 아니지만 데드라인 도달): 취소
            finalization.transition(order, OrderStatus.CANCEL_PENDING, Map.of());
            cancelOrThrow(order);
            KisOrderSnapshot confirmed = confirmOrThrow(order);

            if (confirmed.cumulativeFilledQty() >= order.getQty()) {
                // 레이스: 취소 확정 조회가 전량 체결을 반환 — 체결 우선
                ChainOutcome outcome = finalizeFilled(order, confirmed, barTs, before, ctx,
                        priorAccountedQty, priorNotional);
                return outcome;
            }

            // — 부분/전무 체결 취소 확정
            KisBalance balance = balanceOrThrow();
            long currentAttemptQty = confirmed.cumulativeFilledQty();
            BigDecimal currentNotional = confirmed.cumulativeAvgPrice() == null ? BigDecimal.ZERO
                    : confirmed.cumulativeAvgPrice().multiply(BigDecimal.valueOf(currentAttemptQty));
            long decisionTotalQty = priorAccountedQty + currentAttemptQty;
            long remaining = order.getQty() - currentAttemptQty;
            boolean eod = eodReached(clock.instant(), barTs);
            // add(BUY): 재시도 1회 상한(Task 9). clear(SELL): 잔량이 있는 한 EOD·거부 상한 도달
            // 전까지 반복(Task 10 — REJECTED 분기 자체는 Task 11 범위, 여기서는 상한 미도달 항상
            // true로 취급해 EOD만이 종료 조건이 된다).
            boolean retry = remaining > 0 && (order.getSide() == OrderSide.BUY
                    ? order.getAttempt() <= props.submitRetryMax()
                    : !eod);

            if (retry) {
                // units 증분은 체인이 "끝나는" 호출에서만 한 번 반영한다(재시도로 이어지는 중간
                // CANCELLED 커밋은 shares/cash만 갱신 — units는 이전 값 유지). 그렇지 않으면
                // attempt마다 +1이 중복 반영된다.
                PortfolioState after = applyFill(before, order, barTs, currentAttemptQty, decisionTotalQty,
                        balance, false);
                OrderEntity nextOrder = newAttempt(order, remaining);
                finalization.finalizeChain(order, OrderStatus.CANCELLED, confirmed, after,
                        ChainNext.retry(nextOrder), ctx, TransitionMode.NORMAL);
                order = nextOrder;
                before = after;
                continue;
            }

            if (order.getSide() == OrderSide.SELL && remaining > 0) {
                // clear가 EOD(또는 진행불가)로 잔량을 남긴 채 종료 — 완료가 아니라 이월.
                // units는 이전 값 유지(predict 미호출 — 관찰 없음), shares/cash만 반영.
                PortfolioState after = applyFill(before, order, barTs, currentAttemptQty, decisionTotalQty,
                        balance, false);
                finalization.finalizeChain(order, OrderStatus.CANCELLED, confirmed, after,
                        ChainNext.liquidation(), ctx, TransitionMode.NORMAL);
                alerts.record("PARTIAL_CLEAR_EOD", barTs, Map.of(
                        "decisionId", decisionId, "filledQty", decisionTotalQty, "remainingQty", remaining));
                return new ChainOutcome(after, priorNotional.add(currentNotional), decisionTotalQty, true);
            }

            // 체인 종료 (add 포기 또는 clear 전량 소진): units는 Sigma filled(total) > 0 조건부
            // +1/0 — applyFill이 체인 누계로 이미 판정했다.
            PortfolioState after = applyFill(before, order, barTs, currentAttemptQty, decisionTotalQty,
                    balance, true);
            finalization.finalizeChain(order, OrderStatus.CANCELLED, confirmed, after,
                    ChainNext.markCompleted(), ctx, TransitionMode.NORMAL);
            if (decisionTotalQty > 0) {
                alerts.record("PARTIAL_UNIT", barTs, Map.of(
                        "decisionId", decisionId, "filledQty", decisionTotalQty, "orderedQty", plan.qty()));
            } else {
                alerts.record("EXECUTION_FAILED", barTs, Map.of(
                        "decisionId", decisionId, "orderedQty", plan.qty()));
            }
            return new ChainOutcome(after, priorNotional.add(currentNotional), decisionTotalQty, false);
        }
    }

    /**
     * 폴링이 terminalCandidate(전량 체결 또는 브로커 종결·미취소)로 판단했을 때의 확정 경로.
     * 확정 조회가 실제로 전량 체결을 반환하면 finalize(FILLED)하고 결과를 반환한다. 그렇지 않으면
     * (드묾 — 폴링 스냅숏과 확정 조회 사이 괴리) null을 반환해 호출부가 취소 경로로 폴백한다.
     */
    private ChainOutcome confirmAndFinalizeFilled(OrderEntity order, ZonedDateTime barTs,
            PortfolioState before, ArtifactContext ctx, long priorAccountedQty, BigDecimal priorNotional) {
        KisOrderSnapshot confirmed = confirmOrThrow(order);
        if (confirmed.cumulativeFilledQty() < order.getQty()) {
            return null;
        }
        return finalizeFilled(order, confirmed, barTs, before, ctx, priorAccountedQty, priorNotional);
    }

    private ChainOutcome finalizeFilled(OrderEntity order, KisOrderSnapshot confirmed, ZonedDateTime barTs,
            PortfolioState before, ArtifactContext ctx, long priorAccountedQty, BigDecimal priorNotional) {
        KisBalance balance = balanceOrThrow();
        long currentAttemptQty = confirmed.cumulativeFilledQty();
        BigDecimal currentNotional = confirmed.cumulativeAvgPrice()
                .multiply(BigDecimal.valueOf(currentAttemptQty));
        long decisionTotalQty = priorAccountedQty + currentAttemptQty;
        PortfolioState after = applyFill(before, order, barTs, currentAttemptQty, decisionTotalQty, balance, true);
        finalization.finalizeChain(order, OrderStatus.FILLED, confirmed, after, ChainNext.markCompleted(),
                ctx, TransitionMode.NORMAL);
        return new ChainOutcome(after, priorNotional.add(currentNotional), decisionTotalQty, false);
    }

    /**
     * 잔량 재주문용 다음 attempt — 원 주문 필드 복사 + attempt+1 + qty=잔량 + status=NEW.
     * 저장은 이 메서드가 하지 않는다 — finalization tx 안(ChainNext.retry)에서만 저장된다
     * (체인 원자성, spec §2). package-private — LiquidationService(Task 10)가 재개 attempt
     * 구성에 재사용한다.
     */
    OrderEntity newAttempt(OrderEntity order, long remainingQty) {
        OrderEntity next = new OrderEntity();
        next.setDecisionId(order.getDecisionId());
        next.setAttempt(order.getAttempt() + 1);
        next.setTradingDate(order.getTradingDate());
        next.setSymbol(order.getSymbol());
        next.setSide(order.getSide());
        next.setQty(remainingQty);
        next.setOrderType(order.getOrderType());
        next.setStatus(OrderStatus.NEW);
        next.setAccountedFillNotional(BigDecimal.ZERO);
        return next;
    }

    /** 취소 전송 실패(KisTransportException)는 이 태스크에서는 비종결 유지로 전파. */
    private void cancelOrThrow(OrderEntity order) {
        try {
            broker.cancelOrder(order.getKisOrderNo(), order.getQty() - order.getFilledQty());
        } catch (KisTransportException e) {
            throw new ChainIncompleteException("cancel failed for " + order.getKisOrderNo(), e);
        }
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

    /**
     * 폴링 데드라인 (Task 10) — 15:20 grid 주문(barTs 15:20)은 특례: fillTimeoutMs가 아니라
     * 절대 벽시계 closingAuctionDeadline(기본 15:35 KST)이 데드라인이다(min이 아니라 대체).
     * 그 외 주문은 기존 규칙(제출/재개 시점 + fillTimeoutMs)을 그대로 쓴다.
     */
    private Instant deadline(ZonedDateTime barTs) {
        if (isClosingAuctionOrder(barTs)) {
            return closingAuctionDeadline(barTs);
        }
        return clock.instant().plusMillis(props.fillTimeoutMs());
    }

    /**
     * EOD 판정 (Task 10) — 15:20 특례 주문은 데드라인(closingAuctionDeadline) 도달 자체가 EOD,
     * 그 외 일반 grid 주문은 15:30 KST 이후를 EOD로 본다.
     */
    boolean eodReached(Instant now, ZonedDateTime barTs) {
        ZonedDateTime nowKst = now.atZone(TradingGrid.KST);
        if (isClosingAuctionOrder(barTs)) {
            return !now.isBefore(closingAuctionDeadline(barTs));
        }
        return !nowKst.toLocalTime().isBefore(LocalTime.of(15, 30));
    }

    private boolean isClosingAuctionOrder(ZonedDateTime barTs) {
        return barTs != null
                && barTs.withZoneSameInstant(TradingGrid.KST).toLocalTime().equals(TradingGrid.LAST_BAR);
    }

    /** props.closingAuctionDeadline()("HH:mm")을 barTs가 속한 거래일의 KST 절대시각으로 투영. */
    private Instant closingAuctionDeadline(ZonedDateTime barTs) {
        LocalTime deadline = LocalTime.parse(props.closingAuctionDeadline());
        return barTs.withZoneSameInstant(TradingGrid.KST).toLocalDate()
                .atTime(deadline).atZone(TradingGrid.KST).toInstant();
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
     * units 판정은 decisionTotalQty > 0 기준이지만 **체인이 끝나는 호출(chainEnding)에서만**
     * 반영한다 — 재시도로 이어지는 중간 CANCELLED 커밋(Task 9 attempt 루프)에서도 이 메서드가
     * 호출되므로, chainEnding 없이 매번 +1 하면 attempt 수만큼 중복 반영된다. 체인당 units 증분은
     * 정확히 한 번(총 체결 > 0인 경우)이어야 한다.
     * cash = balance.cashBalance().doubleValue()(예수금 절대값 투영). entry_bar_ts는 1차
     * DryRunExecutor와 동일 규칙: BUY로 처음 진입할 때만 새 bar로 세팅, CLEAR는 초기화.
     * units은 DryRunExecutor와 동일하게 BUY→+1, CLEAR(전량 SELL)→0 — multi-unit
     * (max-units-cap까지 누적 ADD_UNIT)을 RiskGate가 게이트하므로 여기서 1로 고정하면 안 된다.
     */
    private PortfolioState applyFill(PortfolioState before, OrderEntity order, ZonedDateTime barTs,
            long currentAttemptQty, long decisionTotalQty, KisBalance balance, boolean chainEnding) {
        boolean isSell = order.getSide() == OrderSide.SELL;
        double signedDelta = isSell ? -currentAttemptQty : currentAttemptQty;
        double newShares = before.shares() + signedDelta;
        if (!chainEnding || decisionTotalQty <= 0) {
            return new PortfolioState(before.units(), newShares, balance.cashBalance().doubleValue(),
                    before.entryBarTs());
        }
        if (isSell) {
            return new PortfolioState(0, newShares, balance.cashBalance().doubleValue(), null);
        }
        ZonedDateTime entry = before.units() == 0 ? barTs : before.entryBarTs();
        return new PortfolioState(before.units() + 1, newShares, balance.cashBalance().doubleValue(), entry);
    }
}
