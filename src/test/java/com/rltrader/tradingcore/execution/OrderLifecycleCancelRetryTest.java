package com.rltrader.tradingcore.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.*;
import com.rltrader.tradingcore.kis.*;
import com.rltrader.tradingcore.ledger.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Task 9 — 타임아웃 → 취소 → 부분체결 정책 (add/BUY 경로).
 * Clock은 TestMutableClock(주입 Clock을 @Primary로 대체) — 실시간 대기 없이
 * fillTimeoutMs(기본 60s) 도달을 시뮬레이션한다. fill-poll-interval-ms=0으로 폴링 sleep도 무력화.
 */
@SpringBootTest
@TestPropertySource(properties = "order.fill-poll-interval-ms=0")
@Import(OrderLifecycleCancelRetryTest.TestClockConfig.class)
class OrderLifecycleCancelRetryTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new TestMutableClock(Instant.parse("2026-08-07T00:35:00Z"), TradingGrid.KST);
        }
    }

    @Autowired OrderLifecycleService lifecycle;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @Autowired DecisionRepository decisions;
    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired OrderEventRepository orderEvents;
    @Autowired AlertRepository alerts;
    @Autowired Clock clock;
    @Autowired com.rltrader.tradingcore.portfolio.PortfolioManager portfolioManager;
    @Autowired HaltRepository halts;
    @MockitoBean BrokerPort broker;

    @org.junit.jupiter.api.BeforeEach
    void cleanUp() {
        orderEvents.deleteAll();
        executions.deleteAll();
        orders.deleteAll();
        decisions.deleteAll();
        snapshots.deleteAll();
        alerts.deleteAll();
        halts.deleteAll();
        portfolioManager.apply(PortfolioState.flat(10_000));
    }

    private TestMutableClock mutableClock() {
        return (TestMutableClock) clock;
    }

    private long decisionWithNewOrder(long qty) {
        return TestFixtures.decisionWithNewOrder(decisions, orders, qty);
    }

    @Test
    void timeout_cancels_then_retries_once_in_same_finalization_tx() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001", "05360"));
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 7))
                .thenReturn(new KisOrderAck("0002", "05360"));
        // attempt 1: 폴링 1회차 부분(3주) → clock 전진(61s) → 데드라인 도달 → 취소 흐름
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), false, false));
                })
                // 취소 확정 조회: 3주 체결·취소 확정
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), true, true)));
        // attempt 2: 잔량 7주 전량 체결
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 7, 7, new BigDecimal("101.5"), true, false)));
        when(broker.balance())
                .thenReturn(new KisBalance(3, new BigDecimal("9697.00")))   // attempt1 finalization 시점
                .thenReturn(new KisBalance(10, new BigDecimal("8987.50"))); // attempt2 finalization 시점

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        List<OrderEntity> chain = orders.findAll().stream()
                .filter(o -> o.getDecisionId() == d).toList();
        assertThat(chain).hasSize(2);
        OrderEntity attempt1 = chain.stream().filter(o -> o.getAttempt() == 1).findFirst().orElseThrow();
        OrderEntity attempt2 = chain.stream().filter(o -> o.getAttempt() == 2).findFirst().orElseThrow();
        assertThat(attempt1.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(attempt1.getQty()).isEqualTo(10);  // 원 attempt qty는 원래 주문량 유지 (잔량은 신규 attempt의 qty)
        assertThat(attempt2.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(attempt2.getQty()).isEqualTo(7);

        assertThat(out.after().units()).isEqualTo(1);
        assertThat(out.totalFilledQty()).isEqualTo(10);
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(executions.findAll()).hasSize(2);
        assertThat(executions.findAll()).allSatisfy(e ->
                assertThat(List.of(attempt1.getId(), attempt2.getId())).contains(e.getOrderId()));
    }

    @Test
    void retry_also_partial_gives_up_with_partial_unit_alert() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001", "05360"));
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 7))
                .thenReturn(new KisOrderAck("0002", "05360"));
        // attempt 1: 3주 후 취소 (잔량 7)
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), true, true)));
        // attempt 2: 2주 후 취소 (잔량 5 포기 — submitRetryMax=1로 재시도 없음)
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0002", 7, 2, new BigDecimal("101.2"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 7, 2, new BigDecimal("101.2"), true, true)));
        when(broker.balance())
                .thenReturn(new KisBalance(3, new BigDecimal("9697.00")))
                .thenReturn(new KisBalance(5, new BigDecimal("9494.60")));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        OrderEntity attempt2 = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(attempt2.getAttempt()).isEqualTo(2);
        assertThat(attempt2.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(orders.findAll().stream().filter(o -> o.getDecisionId() == d).count()).isEqualTo(2);

        assertThat(out.after().units()).isEqualTo(1);       // Sigma filled 5 > 0 -> 조건부 +1
        assertThat(out.after().shares()).isEqualTo(5.0);
        assertThat(out.after().cash()).isEqualTo(9494.60);
        assertThat(out.totalFilledQty()).isEqualTo(5);

        assertThat(alerts.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_UNIT"));
    }

    @Test
    void zero_fill_chain_leaves_portfolio_unchanged() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001", "05360"), new KisOrderAck("0002", "05360"));
        // attempt 1: 0주 체결 후 취소
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 0, BigDecimal.ZERO, false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 0, BigDecimal.ZERO, true, true)));
        // attempt 2 (잔량 10 그대로): 0주 체결 후 취소, 포기
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0002", 10, 0, BigDecimal.ZERO, false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 10, 0, BigDecimal.ZERO, true, true)));
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("10000.00")));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        assertThat(out.after().units()).isEqualTo(0);
        assertThat(out.after().shares()).isEqualTo(0.0);
        assertThat(out.totalFilledQty()).isEqualTo(0);
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(executions.findAll()).isEmpty();   // zero-fill 종결은 execution 미생성

        assertThat(alerts.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("EXECUTION_FAILED"));
        // "unit 0, shares 잔존" 조합이 생기지 않음 — 위 두 단언(units==0, shares==0.0)이 이미 이를 증명한다.
    }

    @Test
    void fill_wins_cancel_race() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001", "05360"));
        // 폴링: 타임아웃까지 미체결 후보 없음 → 데드라인 도달 → CANCEL_PENDING 커밋 →
        // 취소 확정 조회가 "전량 체결·취소 아님"을 반환 (레이스)
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 4, new BigDecimal("101.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 10, new BigDecimal("101.3"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("8987.00")));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(out.totalFilledQty()).isEqualTo(10);
        // 취소는 실제로 전송됐다(레이스) — 그러나 확정 조회가 전량 체결을 반환해 체결이 이긴다.
        // "취소 실패는 order_events detail로만" — 종결 전이는 FILLED 하나만 기록되고 CANCELLED는
        // 커밋되지 않는다.
        verify(broker, times(1)).cancelOrder(anyString(), anyLong(), any());

        var events = TestFixtures.eventsFor(orderEvents, order);
        assertThat(events).extracting("toStatus").containsSubsequence("CANCEL_PENDING", "FILLED");
        assertThat(events).extracting("toStatus").doesNotContain("CANCELLED");
    }

    /**
     * Fix 3 (P2) — 폴링의 마지막 스냅숏이 이미 brokerTerminal && cancelled(부분 체결인 채 취소
     * 확정)를 보고했다면, CANCEL_PENDING 전이나 cancelOrder POST를 다시 보내지 않고 곧바로 REST
     * 확정 + 부분체결 정책(포기)으로 진행해야 한다. 이미 취소된 주문에 취소를 재요청하면 KIS가
     * 거부해 체인이 비종결로 멈추는 회귀를 방지한다.
     */
    @Test
    void poll_observed_cancelled_skips_recancel_and_finalizes_with_partial_policy() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001", "05360"));
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 7))
                .thenReturn(new KisOrderAck("0002", "05360"));
        // attempt 1: 3주 체결 후 타임아웃 -> 정상 취소 흐름(취소 요청 O) -> 잔량 7, 재시도.
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), true, true)));
        // attempt 2(잔량 7, submitRetryMax=1 도달 — 더 이상 재시도 없음): 폴링 유일 응답이 이미
        // brokerTerminal && cancelled(2주 체결, 취소 확정 관찰) — Fix 3 대상 케이스. (참고:
        // cumulativeFilledQty(2) < qty(7)이고 cancelled=true이므로 filledCandidate는 false —
        // 정상적으로 타임아웃/취소 분기로 진입한다.)
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0002", 7, 2, new BigDecimal("101.0"), true, true));
                });
        when(broker.balance())
                .thenReturn(new KisBalance(3, new BigDecimal("9697.00")))   // attempt1 finalization
                .thenReturn(new KisBalance(5, new BigDecimal("9495.00"))); // attempt2 finalization

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        // attempt1은 정상 취소(취소 요청 1회), attempt2는 이미 관찰된 취소이므로 재요청 없음 —
        // 합쳐서 정확히 1회.
        verify(broker, times(1)).cancelOrder(anyString(), anyLong(), any());

        OrderEntity attempt2 = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(attempt2.getAttempt()).isEqualTo(2);
        assertThat(attempt2.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(attempt2.getAccountedFilledQty()).isEqualTo(2L);
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(out.totalFilledQty()).isEqualTo(5);   // attempt1(3) + attempt2(2)
        assertThat(out.after().units()).isEqualTo(1);

        // attempt2의 이벤트에는 CANCEL_PENDING이 없어야 한다 — 관찰 점프로 곧바로 CANCELLED.
        var attempt2Events = TestFixtures.eventsFor(orderEvents, attempt2);
        assertThat(attempt2Events).extracting("toStatus").doesNotContain("CANCEL_PENDING");
        assertThat(attempt2Events).extracting("toStatus").contains("CANCELLED");

        assertThat(alerts.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_UNIT"));
    }

    @Test
    void in_process_chain_total_includes_prior_attempt_fills() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001", "05360"));
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 7))
                .thenReturn(new KisOrderAck("0002", "05360"));
        // attempt 1: 3주 체결 후 취소 (잔량 7)
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), true, true)));
        // attempt 2: 0주 체결 후 취소 (잔량 포기 — submitRetryMax=1)
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0002", 7, 0, BigDecimal.ZERO, false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 7, 0, BigDecimal.ZERO, true, true)));
        when(broker.balance())
                .thenReturn(new KisBalance(3, new BigDecimal("9697.00")))
                .thenReturn(new KisBalance(3, new BigDecimal("9697.00")));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        assertThat(out.after().units()).isEqualTo(1);     // total 3 > 0
        assertThat(out.after().shares()).isEqualTo(3.0);  // current(attempt2)=0 반영, 이중/누락 없음
        assertThat(out.totalFilledQty()).isEqualTo(3);
        assertThat(alerts.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_UNIT"));
    }

    @Test
    void chain_total_survives_restart_between_attempts() {
        // attempt 1 상태를 DB에 직접 구성: 3주 체결 후 CANCELLED finalization + attempt2 NEW(잔량7)
        // 원자 커밋 결과를 재현한다 (재시작 시뮬 — 메모리 누계 없이 새 runChain 호출).
        long d = decisionWithNewOrder(10);
        OrderEntity attempt1 = orders.findByDecisionIdAndStatus(d, OrderStatus.NEW).orElseThrow();
        attempt1.setKisOrderNo("0001");
        attempt1.setStatus(OrderStatus.CANCELLED);
        attempt1.setFilledQty(3);
        attempt1.setAccountedFilledQty(3);
        attempt1.setAvgFillPrice(new BigDecimal("101.0"));
        attempt1.setAccountedFillNotional(new BigDecimal("303.0"));
        orders.save(attempt1);

        OrderEntity attempt2 = new OrderEntity();
        attempt2.setDecisionId(d);
        attempt2.setAttempt(2);
        attempt2.setTradingDate(attempt1.getTradingDate());
        attempt2.setSymbol(attempt1.getSymbol());
        attempt2.setSide(attempt1.getSide());
        attempt2.setQty(7);
        attempt2.setOrderType(attempt1.getOrderType());
        attempt2.setStatus(OrderStatus.NEW);
        attempt2.setAccountedFillNotional(BigDecimal.ZERO);
        orders.save(attempt2);

        when(broker.submitMarketOrder("005930", OrderSide.BUY, 7))
                .thenReturn(new KisOrderAck("0002", "05360"));
        // attempt 2: 전량 미체결 후 취소 (잔량 포기, submitRetryMax=1이므로 재시도 없음)
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0002", 7, 0, BigDecimal.ZERO, false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 7, 0, BigDecimal.ZERO, true, true)));
        when(broker.balance()).thenReturn(new KisBalance(3, new BigDecimal("9697.00")));

        // "재시작": 새 프로세스 진입 시나리오 — before는 attempt1 커밋분이 이미 반영된 스냅숏이어야
        // 정합. attempt1 자체는 이미 finalize 되어 있으므로 이번 runChain은 attempt2의 NEW를 집는다.
        var before = new PortfolioState(0, 3.0, 9697.00, TestFixtures.bar("2026-08-07T09:10"));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 7),
                TestFixtures.bar("2026-08-07T09:35"), before, TestFixtures.ctx());

        assertThat(out.after().units()).isEqualTo(1);      // DB 복원 누계 Sigma filled=3 > 0
        assertThat(out.after().shares()).isEqualTo(3.0);   // current(attempt2)=0
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(alerts.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_UNIT"));
    }

    @Test
    void intermediate_finalization_keeps_memory_in_sync_on_later_failure() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001", "05360"));
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 7))
                .thenReturn(new KisOrderAck("0002", "05360"));
        // attempt 1: 3주 체결 후 취소 (잔량 7) — 이 finalization은 정상 커밋된다
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), true, true)));
        // attempt 2: 제출 직후 REST 확정 실패(ChainIncomplete) — 폴링 응답은 전량 체결 후보이나
        // 확정 조회(orderSnapshot 2번째 호출)가 전송 계층 예외
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 7, 7, new BigDecimal("101.5"), true, false)))
                .thenThrow(new KisTransportException("read timeout", true, null));
        when(broker.balance())
                .thenReturn(new KisBalance(3, new BigDecimal("9697.00")));

        assertThatThrownBy(() -> lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx()))
                .isInstanceOf(ChainIncompleteException.class);

        // attempt 1의 finalization은 커밋되었고, 그 직후 portfolio.apply(after)가 실행됐어야 한다 —
        // 이후 attempt 2가 실패해도 메모리는 attempt 1 반영 후 snapshot과 일치해야 한다 (stale 금지).
        var snap = snapshots.findById("005930").orElseThrow();
        var mem = portfolioManager.current();
        assertThat(mem.units()).isEqualTo(snap.getUnits());
        assertThat(mem.shares()).isEqualTo(snap.getShares().doubleValue());
        assertThat(mem.cash()).isEqualTo(snap.getCash().doubleValue());
        assertThat(mem.shares()).isEqualTo(3.0);

        OrderEntity attempt2 = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(attempt2.getStatus().isTerminal()).isFalse();   // 비종결 유지 — 복구 대상
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("IN_PROGRESS");
    }

    /**
     * Fix A(2차 리뷰, P1) — 제출 ack에 KRX_FWDG_ORD_ORGNO가 없고(null) 이후 폴링 스냅숏에도 한
     * 번도 채워지지 않은 채 타임아웃에 도달하면, cancelOrder POST를 보내지 않고 즉시
     * HALT(CANCEL_ORGNO_MISSING) + ChainIncompleteException — 공백 전송 금지(공식 계약상 필수·
     * 비공백 필드, blank 전송은 반복 거부로 이어져 체인이 고착된다).
     */
    @Test
    void cancel_without_org_no_halts_instead_of_sending_blank() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001"));   // org no 없음(null) — 단일 인자 편의 생성자
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    // brokerTerminal/cancelled 모두 false — org no 필드도 없음(부재)
                    return Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), false, false));
                });

        assertThatThrownBy(() -> lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx()))
                .isInstanceOf(ChainIncompleteException.class);

        verify(broker, never()).cancelOrder(anyString(), anyLong(), any());

        assertThat(halts.findByClearedAtIsNull())
                .anySatisfy(h -> assertThat(h.getCode()).isEqualTo("CANCEL_ORGNO_MISSING"));

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus().isTerminal()).isFalse();   // 비종결 유지 — 수동 개입 대상
    }

    /**
     * Fix A 대응 — org no가 있으면(제출 ack에서 확보) 취소가 정상 전송된다. 회귀 방지용 대조군.
     */
    @Test
    void cancel_with_org_no_sends_normally() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001", "05360"));
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 7))
                .thenReturn(new KisOrderAck("0002", "05360"));
        // attempt 1: 3주 체결 후 타임아웃 -> 취소(정상 전송, org no 있음) -> 잔량 7, 재시도.
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 3, new BigDecimal("101.0"), true, true)));
        // attempt 2(잔량 7, submitRetryMax=1 도달): 전량 체결.
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 7, 7, new BigDecimal("101.5"), true, false)));
        when(broker.balance())
                .thenReturn(new KisBalance(3, new BigDecimal("9697.00")))
                .thenReturn(new KisBalance(10, new BigDecimal("8987.50")));

        lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        // attempt1의 취소만 전송된다(attempt2는 전량 체결로 취소 경로 자체를 타지 않음).
        verify(broker, times(1)).cancelOrder(eq("0001"), anyLong(), eq("05360"));
        assertThat(halts.findByClearedAtIsNull()).isEmpty();
    }
}
