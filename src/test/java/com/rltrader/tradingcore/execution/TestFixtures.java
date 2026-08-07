package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradingGrid;
import com.rltrader.tradingcore.ledger.DecisionEntity;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderEventEntity;
import com.rltrader.tradingcore.ledger.OrderEventRepository;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;

/** Task 8+ 통합 테스트 공용 픽스처 — decision+NEW order 생성, bar/ctx 헬퍼. */
final class TestFixtures {

    private TestFixtures() {}

    static final ArtifactContext CTX = new ArtifactContext("ppo-fs3-x", 3, 0.199, 10_000.0, 5);

    /** OrderPersistenceTest.newDecision()과 동일한 IN_PROGRESS decision + NEW order를 만들어 decisionId 반환. */
    static long decisionWithNewOrder(DecisionRepository decisions, OrderRepository orders, long qty) {
        DecisionEntity d = new DecisionEntity();
        d.setSymbol("005930");
        d.setBarTs(OffsetDateTime.now());
        d.setAction(1);
        d.setLabel("add_unit");
        d.setActionMask("[]");
        d.setObservation("[]");
        d.setBarClose(100.0);
        d.setArtifactId(CTX.artifactId());
        d.setExecutionStatus("IN_PROGRESS");
        decisions.save(d);

        OrderEntity o = new OrderEntity();
        o.setDecisionId(d.getId());
        o.setAttempt(1);
        o.setTradingDate(LocalDate.of(2026, 8, 7));
        o.setSymbol("005930");
        o.setSide(OrderSide.BUY);
        o.setQty(qty);
        o.setOrderType("MARKET");
        o.setStatus(OrderStatus.NEW);
        o.setAccountedFillNotional(BigDecimal.ZERO);
        orders.save(o);

        return d.getId();
    }

    static ZonedDateTime bar(String kstLocalDateTime) {
        return ZonedDateTime.of(LocalDateTime.parse(kstLocalDateTime), TradingGrid.KST);
    }

    static PortfolioState flat(double cash) {
        return PortfolioState.flat(cash);
    }

    static ArtifactContext ctx() {
        return CTX;
    }

    static List<OrderEventEntity> eventsFor(OrderEventRepository orderEvents, OrderEntity order) {
        return orderEvents.findByOrderIdOrderByIdAsc(order.getId());
    }
}
