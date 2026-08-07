package com.rltrader.tradingcore.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.*;
import com.rltrader.tradingcore.kis.*;
import com.rltrader.tradingcore.ledger.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(properties = "order.fill-poll-interval-ms=0")
class OrderLifecycleHappyPathTest extends AbstractIntegrationTest {

    @Autowired OrderLifecycleService lifecycle;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @Autowired DecisionRepository decisions;
    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired OrderEventRepository orderEvents;
    @MockitoBean BrokerPort broker;

    @org.junit.jupiter.api.BeforeEach
    void cleanUp() {
        // 테스트 간 순서 의존 제거 — kis_order_no("0001")가 같은 trading_date에서
        // 재사용되므로 uq_orders_trading_date_kis_no 충돌을 막는다 (OrderPersistenceTest와 동일 관례)
        orderEvents.deleteAll();
        executions.deleteAll();
        orders.deleteAll();
        decisions.deleteAll();
        snapshots.deleteAll();
    }

    // 헬퍼: IN_PROGRESS decision + NEW order를 tx1 상당으로 만들어 둔다 (Task 12 전까지 수동)
    private long decisionWithNewOrder(long qty) { /* Task 4 테스트의 newDecision() + OrderEntity NEW 저장, decisionId 반환 */
        // (구현 시 OrderPersistenceTest 헬퍼 재사용 — 코드 동일하므로 테스트 픽스처로 추출)
        return TestFixtures.decisionWithNewOrder(decisions, orders, qty);
    }

    @Test
    void full_fill_finalizes_atomically_and_marks_completed() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001"));
        // 폴링 1회차: 전량 체결 후보 → REST 확정 조회
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot(
                        "0001", 10, 10, new BigDecimal("101.5"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("8985.00")));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        OrderEntity o = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(o.getFilledQty()).isEqualTo(10L);
        assertThat(o.getAccountedFilledQty()).isEqualTo(10L);        // finalization에서 accounted = filled
        assertThat(o.getAvgFillPrice()).isEqualByComparingTo("101.5");
        assertThat(executions.findAll()).anySatisfy(e ->
                assertThat(e.getOrderId()).isEqualTo(o.getId()));
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus())
                .isEqualTo("COMPLETED");                             // 체인 다음 상태가 같은 tx
        assertThat(out.after().units()).isEqualTo(1);
        assertThat(out.after().cash()).isEqualTo(8985.00);           // 예수금 절대값 투영 (spec §6)
        assertThat(snapshots.findById("005930").orElseThrow().getUnits()).isEqualTo(1);
    }

    @Test
    void rest_confirm_failure_leaves_order_nonterminal() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder(any(), any(), anyLong())).thenReturn(new KisOrderAck("0001"));
        when(broker.orderSnapshot(eq("0001"), any()))
                .thenReturn(Optional.of(new KisOrderSnapshot(
                        "0001", 10, 10, new BigDecimal("101.5"), true, false)))
                .thenThrow(new KisTransportException("read timeout", true, null));
        when(broker.balance()).thenThrow(new KisTransportException("read timeout", true, null));

        assertThatThrownBy(() -> lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx()))
                .isInstanceOf(ChainIncompleteException.class);

        OrderEntity o = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(o.getStatus().isTerminal()).isFalse();            // terminal 소유권: 확정 실패 → 비종결 유지
        assertThat(o.getFilledQty()).isEqualTo(10L);                 // 수량 진급은 반영
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus())
                .isEqualTo("IN_PROGRESS");                           // 복구가 집어 들 수 있음
    }

    @Test
    void second_add_unit_increments_existing_units_and_preserves_entry_bar() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.BUY, 10))
                .thenReturn(new KisOrderAck("0001"));
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot(
                        "0001", 10, 10, new BigDecimal("101.5"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(20, new BigDecimal("6985.00")));

        // 이미 unit 1개(shares 10) 보유 상태에서 두 번째 ADD_UNIT 체인 — RiskGate가
        // max-units-cap(기본 5)까지 허용하는 multi-unit 시나리오 (RiskGateTest 참고)
        var entryBar = TestFixtures.bar("2026-08-07T09:10");
        var before = new PortfolioState(1, 10.0, 8985.00, entryBar);

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), before, TestFixtures.ctx());

        assertThat(out.after().units()).isEqualTo(2);                // +1, 아니라 하드코딩된 1이 아님
        assertThat(out.after().shares()).isEqualTo(20.0);             // prior(10) + current(10)
        assertThat(out.after().entryBarTs()).isEqualTo(entryBar);     // 최초 진입 bar 유지 (1차 규칙)
        assertThat(snapshots.findById("005930").orElseThrow().getUnits()).isEqualTo(2);
    }

    @Test
    void intermediate_transitions_are_committed_individually() {
        long d = decisionWithNewOrder(10);
        when(broker.submitMarketOrder(any(), any(), anyLong())).thenReturn(new KisOrderAck("0001"));
        // 폴링 1회차 부분 체결 → 2회차 전량
        when(broker.orderSnapshot(eq("0001"), any()))
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 4, new BigDecimal("101.0"), false, false)))
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 10, new BigDecimal("101.5"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("8985.00")));

        lifecycle.runChain(d, new OrderPlan(OrderSide.BUY, 10),
                TestFixtures.bar("2026-08-07T09:35"), TestFixtures.flat(10_000), TestFixtures.ctx());

        var events = TestFixtures.eventsFor(orderEvents,
                orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow());
        // NEW→SUBMITTED, SUBMITTED→ACKED, ACKED→PARTIALLY_FILLED, PARTIALLY_FILLED→FILLED
        assertThat(events).extracting("toStatus")
                .containsExactly("SUBMITTED", "ACKED", "PARTIALLY_FILLED", "FILLED");
    }
}
