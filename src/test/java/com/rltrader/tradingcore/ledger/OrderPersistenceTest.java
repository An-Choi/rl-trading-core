package com.rltrader.tradingcore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrderPersistenceTest extends AbstractIntegrationTest {

    @Autowired OrderRepository orders;
    @Autowired DecisionRepository decisions;
    @Autowired HaltRepository halts;

    @org.junit.jupiter.api.BeforeEach
    void cleanUp() {
        // 테스트 간 순서 의존 제거 (PortfolioManagerRestoreTest와 동일 관례)
        orders.deleteAll();
        halts.deleteAll();
        decisions.deleteAll();
    }

    private DecisionEntity newDecision() {
        DecisionEntity d = new DecisionEntity();
        d.setSymbol("005930");
        d.setBarTs(java.time.OffsetDateTime.now());
        d.setAction(1); d.setLabel("add_unit");
        d.setActionMask("[]"); d.setObservation("[]");
        d.setBarClose(100.0); d.setArtifactId("a1");
        d.setExecutionStatus("IN_PROGRESS");
        return decisions.save(d);
    }

    @Test
    void saves_and_finds_nonterminal_orders() {
        DecisionEntity d = newDecision();
        OrderEntity o = new OrderEntity();
        o.setDecisionId(d.getId()); o.setAttempt(1);
        o.setTradingDate(LocalDate.of(2026, 8, 7));
        o.setSymbol("005930"); o.setSide(OrderSide.BUY);
        o.setQty(10L); o.setOrderType("MARKET");
        o.setStatus(OrderStatus.ACKED);
        o.setAccountedFillNotional(BigDecimal.ZERO);
        orders.save(o);

        List<OrderEntity> open = orders.findByStatusIn(List.of(
                OrderStatus.NEW, OrderStatus.SUBMITTED, OrderStatus.SUBMIT_UNKNOWN,
                OrderStatus.ACKED, OrderStatus.PARTIALLY_FILLED, OrderStatus.CANCEL_PENDING));
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getSide()).isEqualTo(OrderSide.BUY);
    }

    @Test
    void halt_uncleared_lookup() {
        HaltEntity h = new HaltEntity();
        h.setCode("RECON_UNEXPLAINED");
        h.setDetail("{\"why\":\"test\"}");
        halts.save(h);
        assertThat(halts.findByClearedAtIsNull()).hasSize(1);
    }

    @Test
    void decision_execution_status_lookup() {
        newDecision();
        assertThat(decisions.findByExecutionStatus("IN_PROGRESS")).hasSize(1);
    }
}
