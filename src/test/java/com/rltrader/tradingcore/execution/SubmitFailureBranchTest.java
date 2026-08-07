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
 * Task 11 — 제출 지점의 REJECTED/ABORTED/SUBMIT_UNKNOWN 분기.
 * fill-poll-interval-ms=0으로 폴링 sleep 무력화, TestMutableClock으로 벽시계 제어(Task 9/10과 동일 구조).
 */
@SpringBootTest
@TestPropertySource(properties = "order.fill-poll-interval-ms=0")
@Import(SubmitFailureBranchTest.TestClockConfig.class)
class SubmitFailureBranchTest extends AbstractIntegrationTest {

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
    @Autowired HaltRepository halts;
    @Autowired Clock clock;
    @Autowired com.rltrader.tradingcore.portfolio.PortfolioManager portfolioManager;
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

    private long decisionWithNewOrder(long qty) {
        return TestFixtures.decisionWithNewOrder(decisions, orders, qty);
    }

    private long clearDecisionWithNewOrder(long qty) {
        return TestFixtures.clearDecisionWithNewOrder(decisions, orders, qty);
    }

    @Test
    void add_rejection_terminates_chain_unchanged() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenThrow(new KisOrderRejectedException("1", "insufficient balance"));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getAttempt()).isEqualTo(1);

        var events = TestFixtures.eventsFor(orderEvents, order);
        assertThat(events).extracting("toStatus").containsExactly("SUBMITTED", "REJECTED");

        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(out.after().units()).isEqualTo(0);
        assertThat(out.after().shares()).isEqualTo(0.0);
        assertThat(out.totalFilledQty()).isEqualTo(0);
        assertThat(executions.findAll()).isEmpty();   // zero-fill 종결 — execution 미생성

        assertThat(alerts.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("ORDER_REJECTED"));
        verify(broker, times(1)).submitMarketOrder(any(), any(), anyLong());
    }

    @Test
    void clear_rejection_retries_then_carries_over() {
        // clear(SELL) 3연속 거부 -> rejectRetryMax(3) 도달 -> 이월(liquidationPending=true)
        long d = clearDecisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.SELL, 10))
                .thenThrow(new KisOrderRejectedException("1", "rejected-1"))
                .thenThrow(new KisOrderRejectedException("1", "rejected-2"))
                .thenThrow(new KisOrderRejectedException("1", "rejected-3"));

        var before = new PortfolioState(1, 10.0, 0.0, TestFixtures.bar("2026-08-07T09:10"));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.SELL, 10),
                TestFixtures.bar("2026-08-07T09:35"), before, TestFixtures.ctx());

        List<OrderEntity> chain = orders.findAll().stream()
                .filter(o -> o.getDecisionId() == d).toList();
        // 상한 도달까지 매 거부 시도가 하나의 attempt를 소비했는지 여부는 구현에 맡기되,
        // 최종적으로 이월(liquidationPending=true) + decision IN_PROGRESS(미완)만 검증한다.
        assertThat(snapshots.findById("005930").orElseThrow().isLiquidationPending()).isTrue();
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("IN_PROGRESS");
        assertThat(out.liquidationCarried()).isTrue();

        verify(broker, times(3)).submitMarketOrder(eq("005930"), eq(OrderSide.SELL), anyLong());
        assertThat(chain).isNotEmpty();
    }

    @Test
    void unsent_failure_retries_then_aborts() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenThrow(new KisTransportException("connect refused", false, null))
                .thenThrow(new KisTransportException("connect refused", false, null));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ABORTED);
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");

        // 송신된 적이 없다 -- SUBMITTED 이벤트가 기록되면 안 된다.
        var events = TestFixtures.eventsFor(orderEvents, order);
        assertThat(events).extracting("toStatus").doesNotContain("SUBMITTED");
        assertThat(events).extracting("toStatus").containsExactly("ABORTED");

        assertThat(out.after().units()).isEqualTo(0);
        assertThat(out.totalFilledQty()).isEqualTo(0);
        assertThat(executions.findAll()).isEmpty();

        verify(broker, times(2)).submitMarketOrder(any(), any(), anyLong());
    }

    @Test
    void submit_unknown_adopts_discovered_order() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenThrow(new KisTransportException("read timeout", true, null));

        // dailyOrders: 로컬이 모르는 kis_order_no "0009" 1건만 반환 -- 채택 대상
        when(broker.dailyOrders(LocalDate.of(2026, 8, 7)))
                .thenReturn(List.of(new KisOrderSnapshot("0009", 10, 0, BigDecimal.ZERO, false, false)));

        // 채택 이후 폴링 합류: 전량 체결
        when(broker.orderSnapshot(eq("0009"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("0009", 10, 10, new BigDecimal("101.5"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("8985.00")));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getKisOrderNo()).isEqualTo("0009");

        var events = TestFixtures.eventsFor(orderEvents, order);
        assertThat(events).extracting("fromStatus", "toStatus")
                .contains(org.assertj.core.groups.Tuple.tuple("SUBMIT_UNKNOWN", "ACKED"));

        assertThat(out.totalFilledQty()).isEqualTo(10);
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(halts.findAll()).isEmpty();

        verify(broker, times(1)).submitMarketOrder(any(), any(), anyLong());
    }

    @Test
    void submit_unknown_ambiguous_halts_without_resend() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenThrow(new KisTransportException("read timeout", true, null));

        // dailyOrders: 로컬이 모르는 kis_order_no 2건 -- 모호 -> HALT, 재전송 없음
        when(broker.dailyOrders(LocalDate.of(2026, 8, 7)))
                .thenReturn(List.of(
                        new KisOrderSnapshot("0009", 10, 0, BigDecimal.ZERO, false, false),
                        new KisOrderSnapshot("0010", 10, 0, BigDecimal.ZERO, false, false)));

        assertThatThrownBy(() -> lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx()))
                .isInstanceOf(ChainIncompleteException.class);

        verify(broker, times(1)).submitMarketOrder(any(), any(), anyLong());

        assertThat(halts.findAll()).anySatisfy(h -> assertThat(h.getCode()).isEqualTo("SUBMIT_UNKNOWN"));

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUBMIT_UNKNOWN);
        assertThat(order.getStatus().isTerminal()).isFalse();
    }

    @Test
    void submit_unknown_not_found_after_max_lookups_halts() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenThrow(new KisTransportException("read timeout", true, null));

        // dailyOrders: 로컬 미상 주문 0건, 매 조회마다 반복 (submitUnknownMaxLookups=5, 기본값)
        when(broker.dailyOrders(LocalDate.of(2026, 8, 7))).thenReturn(List.of());

        assertThatThrownBy(() -> lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx()))
                .isInstanceOf(ChainIncompleteException.class);

        verify(broker, times(1)).submitMarketOrder(any(), any(), anyLong());
        verify(broker, times(5)).dailyOrders(LocalDate.of(2026, 8, 7));

        assertThat(halts.findAll()).anySatisfy(h -> assertThat(h.getCode()).isEqualTo("SUBMIT_UNKNOWN"));

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUBMIT_UNKNOWN);
    }
}
