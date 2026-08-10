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
 * Task 10 — clear(SELL) 체인 지속 · EOD/진행불가 이월(LIQUIDATION_PENDING) · 재개 + 15:20 특례.
 * 구조는 Task 9(OrderLifecycleCancelRetryTest)와 동일 — TestMutableClock으로 벽시계 진행을
 * 시뮬레이션한다.
 */
@SpringBootTest
@TestPropertySource(properties = "order.fill-poll-interval-ms=0")
@Import(ClearChainAndLiquidationTest.TestClockConfig.class)
class ClearChainAndLiquidationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new TestMutableClock(Instant.parse("2026-08-07T00:35:00Z"), TradingGrid.KST);
        }
    }

    @Autowired OrderLifecycleService lifecycle;
    @Autowired LiquidationService liquidation;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @Autowired DecisionRepository decisions;
    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired OrderEventRepository orderEvents;
    @Autowired AlertRepository alerts;
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
    }

    private TestMutableClock mutableClock() {
        return (TestMutableClock) clock;
    }

    private long clearDecisionWithNewOrder(long qty) {
        return TestFixtures.clearDecisionWithNewOrder(decisions, orders, qty);
    }

    @Test
    void clear_keeps_retrying_until_zero_remaining() {
        // 보유 10주 clear: attempt1 4주->취소, attempt2 3주->취소, attempt3 3주 전량 -> FILLED
        long d = clearDecisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.SELL, 10))
                .thenReturn(new KisOrderAck("0001"));
        when(broker.submitMarketOrder("005930", OrderSide.SELL, 6))
                .thenReturn(new KisOrderAck("0002"));
        when(broker.submitMarketOrder("005930", OrderSide.SELL, 3))
                .thenReturn(new KisOrderAck("0003"));

        // attempt 1: 4주 체결 후 타임아웃 -> 취소 확정 (잔량 6)
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 4, new BigDecimal("100.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 4, new BigDecimal("100.0"), true, true)));
        // attempt 2: 3주 체결 후 타임아웃 -> 취소 확정 (잔량 3)
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0002", 6, 3, new BigDecimal("100.2"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 6, 3, new BigDecimal("100.2"), true, true)));
        // attempt 3: 3주 전량 체결
        when(broker.orderSnapshot(eq("0003"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("0003", 3, 3, new BigDecimal("100.5"), true, false)));

        when(broker.balance())
                .thenReturn(new KisBalance(6, new BigDecimal("400.00")))   // attempt1 finalization
                .thenReturn(new KisBalance(3, new BigDecimal("700.60")))   // attempt2 finalization
                .thenReturn(new KisBalance(0, new BigDecimal("1001.10"))); // attempt3 finalization (final)

        var before = new PortfolioState(1, 10.0, 0.0, TestFixtures.bar("2026-08-07T09:10"));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.SELL, 10),
                TestFixtures.bar("2026-08-07T09:35"), before, TestFixtures.ctx());

        List<OrderEntity> chain = orders.findAll().stream()
                .filter(o -> o.getDecisionId() == d).toList();
        assertThat(chain).hasSize(3);
        assertThat(chain.stream().map(OrderEntity::getAttempt).sorted().toList()).containsExactly(1, 2, 3);

        OrderEntity attempt3 = chain.stream().filter(o -> o.getAttempt() == 3).findFirst().orElseThrow();
        assertThat(attempt3.getStatus()).isEqualTo(OrderStatus.FILLED);

        assertThat(out.after().units()).isEqualTo(0);
        assertThat(out.after().entryBarTs()).isNull();
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(out.totalFilledQty()).isEqualTo(10);
        assertThat(snapshots.findById("005930").orElseThrow().isLiquidationPending()).isFalse();
        // rejectRetryMax(기본 3) 미도달 — 거부 상한 소진으로 끝난 게 아니라 잔량 소진으로 끝남을 방증
        assertThat(chain).allSatisfy(o -> assertThat(o.getAttempt()).isLessThanOrEqualTo(3));
    }

    @Test
    void clear_eod_carries_liquidation_pending_atomically() {
        // 15:25 bar에서 clear, 잔량 6 남은 채 clock을 15:31로 전진 -> EOD 이월
        long d = clearDecisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.SELL, 10))
                .thenReturn(new KisOrderAck("0001"));
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));  // 15:36:01 -> 15:31 진입은 아래에서 별도 처리
                    return Optional.of(new KisOrderSnapshot("0001", 10, 4, new BigDecimal("100.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 4, new BigDecimal("100.0"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(6, new BigDecimal("400.00")));

        // clock을 15:25로 맞춘 뒤 실행 (bar=15:25, clock 시작 15:25, 폴링 중 61초 전진 -> 15:26:01,
        // 데드라인은 fillTimeoutMs 60s로 아직 EOD 아님 -> 취소 흐름 진입 후 확정 조회 시점엔
        // 아직 15:30 이전일 수 있으므로, EOD 판정은 취소 시도 이전에 clock을 15:31로 직접 밀어 검증한다)
        mutableClock().set(Instant.parse("2026-08-07T06:20:00Z")); // 15:20 KST (미사용, 재설정 대비)
        mutableClock().set(Instant.parse("2026-08-07T06:25:00Z")); // 15:25 KST

        var before = new PortfolioState(1, 10.0, 0.0, TestFixtures.bar("2026-08-07T09:10"));

        // 폴링 도중 61초 전진 -> 15:26:01(아직 EOD 아님) -> 타임아웃 -> 취소 확정 -> 잔량 6.
        // 취소 확정 이후 재시도 여부 판정 시점에 EOD 검사를 하므로, 취소 확정 응답 반환 직후
        // clock을 15:31로 미리 전진시켜 판정 시점에 EOD가 성립하도록 attempt1 두 번째
        // orderSnapshot 응답(취소 확정)에서 전진시킨다.
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0001", 10, 4, new BigDecimal("100.0"), false, false));
                })
                .thenAnswer(inv -> {
                    mutableClock().set(Instant.parse("2026-08-07T06:31:00Z")); // 15:31 KST
                    return Optional.of(new KisOrderSnapshot("0001", 10, 4, new BigDecimal("100.0"), true, true));
                });

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.SELL, 10),
                TestFixtures.bar("2026-08-07T15:25"), before, TestFixtures.ctx());

        OrderEntity attempt1 = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(attempt1.getAttempt()).isEqualTo(1);
        assertThat(attempt1.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        var snap = snapshots.findById("005930").orElseThrow();
        assertThat(snap.isLiquidationPending()).isTrue();

        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("IN_PROGRESS");

        // units는 이전 값 유지 (predict 호출 없음 - 관찰 안 됨)
        assertThat(out.after().units()).isEqualTo(1);
        assertThat(out.after().shares()).isEqualTo(6.0);   // shares/cash는 반영됨
        assertThat(out.after().cash()).isEqualTo(400.00);

        assertThat(alerts.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_CLEAR_EOD"));

        // 후속 attempt(NEW)가 새로 만들어지지 않음 -- 이월은 재개(LiquidationService)의 몫
        assertThat(orders.findAll().stream().filter(o -> o.getDecisionId() == d).count()).isEqualTo(1);
    }

    @Test
    void liquidation_resume_completes_and_clears_flag() {
        // 전일 상태를 DB에 직접 구성: attempt1 CANCELLED(4주 체결) + liquidationPending=true
        long d = clearDecisionWithNewOrder(10);
        OrderEntity attempt1 = orders.findByDecisionIdAndStatus(d, OrderStatus.NEW).orElseThrow();
        attempt1.setKisOrderNo("0001");
        attempt1.setStatus(OrderStatus.CANCELLED);
        attempt1.setFilledQty(4);
        attempt1.setAccountedFilledQty(4);
        attempt1.setAvgFillPrice(new BigDecimal("100.0"));
        attempt1.setAccountedFillNotional(new BigDecimal("400.0"));
        orders.save(attempt1);

        PortfolioSnapshotEntity snap = new PortfolioSnapshotEntity();
        snap.setSymbol("005930");
        snap.setUnits(1);
        snap.setShares(6.0);
        snap.setCash(400.00);
        snap.setEntryBarTs(TestFixtures.bar("2026-08-07T09:10").toOffsetDateTime());
        snap.setArtifactId(TestFixtures.ctx().artifactId());
        snap.setUpdatedAt(java.time.OffsetDateTime.now());
        snap.setLiquidationPending(true);
        snapshots.save(snap);
        portfolioManager.apply(new PortfolioState(1, 6.0, 400.00, TestFixtures.bar("2026-08-07T09:10")));

        // 익일 09:00로 clock 전진
        mutableClock().set(Instant.parse("2026-08-08T00:00:00Z")); // 09:00 KST 8/8

        when(broker.submitMarketOrder("005930", OrderSide.SELL, 6))
                .thenReturn(new KisOrderAck("0002"));
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 6, 6, new BigDecimal("99.0"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("994.00")));

        assertThat(liquidation.pendingActive()).isTrue();

        ChainOutcome out = liquidation.resume(TestFixtures.bar("2026-08-08T09:00"));

        List<OrderEntity> chain = orders.findAll().stream()
                .filter(o -> o.getDecisionId() == d).toList();
        assertThat(chain).hasSize(2);
        OrderEntity attempt2 = chain.stream().filter(o -> o.getAttempt() == 2).findFirst().orElseThrow();
        assertThat(attempt2.getDecisionId()).isEqualTo(d);
        assertThat(attempt2.getStatus()).isEqualTo(OrderStatus.FILLED);

        var snapAfter = snapshots.findById("005930").orElseThrow();
        assertThat(snapAfter.isLiquidationPending()).isFalse();
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");

        assertThat(out.after().units()).isEqualTo(0);
        assertThat(out.after().entryBarTs()).isNull();
        assertThat(liquidation.pendingActive()).isFalse();
    }

    /**
     * 리뷰 수정(Critical) 커버 테스트 — 재개된 이월 체인의 첫 재개 attempt 자체가 부분체결 후
     * 타임아웃->취소->재시도(잔량 있음, 아직 EOD 아님)로 이어질 때, 그 중간 finalization이
     * liquidationPending을 하드코딩된 false로 조용히 꺼버리지 않고 true로 재확언해야 한다 —
     * 그래야 pendingActive()의 유일한 스캐너가 그 시점에 크래시해도 이 decision을 계속 찾는다.
     * 최종적으로 체인이 완료되면 그때에서야 플래그가 false가 된다.
     */
    @Test
    void liquidation_resume_intermediate_retry_preserves_liquidation_pending_until_completion() {
        // 전일 상태: attempt1 CANCELLED(4주 체결, 잔량 6) + liquidationPending=true
        long d = clearDecisionWithNewOrder(10);
        OrderEntity attempt1 = orders.findByDecisionIdAndStatus(d, OrderStatus.NEW).orElseThrow();
        attempt1.setKisOrderNo("0001");
        attempt1.setStatus(OrderStatus.CANCELLED);
        attempt1.setFilledQty(4);
        attempt1.setAccountedFilledQty(4);
        attempt1.setAvgFillPrice(new BigDecimal("100.0"));
        attempt1.setAccountedFillNotional(new BigDecimal("400.0"));
        orders.save(attempt1);

        PortfolioSnapshotEntity snap = new PortfolioSnapshotEntity();
        snap.setSymbol("005930");
        snap.setUnits(1);
        snap.setShares(6.0);
        snap.setCash(400.00);
        snap.setEntryBarTs(TestFixtures.bar("2026-08-07T09:10").toOffsetDateTime());
        snap.setArtifactId(TestFixtures.ctx().artifactId());
        snap.setUpdatedAt(java.time.OffsetDateTime.now());
        snap.setLiquidationPending(true);
        snapshots.save(snap);
        portfolioManager.apply(new PortfolioState(1, 6.0, 400.00, TestFixtures.bar("2026-08-07T09:10")));

        // 익일 09:00로 clock 전진 (아직 15:30 KST 훨씬 이전 -- EOD 아님)
        mutableClock().set(Instant.parse("2026-08-08T00:00:00Z")); // 09:00 KST 8/8

        // resume()이 재개하는 attempt2(잔량 6): 2주 체결 후 타임아웃 -> 취소 확정 (잔량 4, EOD
        // 아님) -> retry -> attempt3(잔량 4) 생성. attempt3 제출 시점에 attempt2의 중간
        // finalization이 이미 커밋돼 있어야 하므로, attempt3의 submitMarketOrder 호출 시점에
        // snapshot.liquidationPending이 여전히 true인지 확인한다.
        when(broker.submitMarketOrder("005930", OrderSide.SELL, 6))
                .thenReturn(new KisOrderAck("0002"));
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot("0002", 6, 2, new BigDecimal("99.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0002", 6, 2, new BigDecimal("99.0"), true, true)));

        when(broker.submitMarketOrder("005930", OrderSide.SELL, 4))
                .thenAnswer(inv -> {
                    // attempt2의 CANCELLED+retry finalization은 이 시점에 이미 커밋되어 있다
                    // (동기 호출 체인) -- 여기서 스냅숏을 읽어 중간 상태를 검증한다.
                    var midChain = snapshots.findById("005930").orElseThrow();
                    assertThat(midChain.isLiquidationPending())
                            .as("intermediate retry commit must re-affirm liquidationPending=true")
                            .isTrue();
                    assertThat(decisions.findById(d).orElseThrow().getExecutionStatus())
                            .isEqualTo("IN_PROGRESS");
                    return new KisOrderAck("0003");
                });
        // attempt3: 잔량 4 전량 체결 -> 체인 완료
        when(broker.orderSnapshot(eq("0003"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("0003", 4, 4, new BigDecimal("99.2"), true, false)));

        when(broker.balance())
                .thenReturn(new KisBalance(4, new BigDecimal("598.00")))    // attempt2 finalization
                .thenReturn(new KisBalance(0, new BigDecimal("994.80")));   // attempt3 finalization (final)

        assertThat(liquidation.pendingActive()).isTrue();

        ChainOutcome out = liquidation.resume(TestFixtures.bar("2026-08-08T09:00"));

        List<OrderEntity> chain = orders.findAll().stream()
                .filter(o -> o.getDecisionId() == d).toList();
        assertThat(chain).hasSize(3);   // attempt1(전일) + attempt2 + attempt3
        OrderEntity attempt2 = chain.stream().filter(o -> o.getAttempt() == 2).findFirst().orElseThrow();
        OrderEntity attempt3 = chain.stream().filter(o -> o.getAttempt() == 3).findFirst().orElseThrow();
        assertThat(attempt2.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(attempt3.getStatus()).isEqualTo(OrderStatus.FILLED);

        // 완료 후에야 플래그가 꺼진다
        var snapAfter = snapshots.findById("005930").orElseThrow();
        assertThat(snapAfter.isLiquidationPending()).isFalse();
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(out.after().units()).isEqualTo(0);
        assertThat(out.after().entryBarTs()).isNull();
        assertThat(liquidation.pendingActive()).isFalse();
    }

    @Test
    void closing_auction_order_waits_until_1535_then_fills() {
        // 15:20 bar 주문: clock 15:25 -- 미체결 스냅숏이어도 취소하지 않고 폴링 지속.
        // clock 15:31 -- 단일가 체결 결과 스냅숏 수신 -> FILLED.
        long d = clearDecisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.SELL, 10))
                .thenReturn(new KisOrderAck("0001"));

        mutableClock().set(Instant.parse("2026-08-07T06:20:00Z")); // 15:20 KST

        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().set(Instant.parse("2026-08-07T06:25:00Z")); // 15:25 KST -- 아직 미체결
                    return Optional.of(new KisOrderSnapshot("0001", 10, 0, BigDecimal.ZERO, false, false));
                })
                .thenAnswer(inv -> {
                    mutableClock().set(Instant.parse("2026-08-07T06:31:00Z")); // 15:31 KST -- 단일가 전량 체결
                    return Optional.of(new KisOrderSnapshot("0001", 10, 10, new BigDecimal("99.5"), true, false));
                });
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("995.00")));

        var before = new PortfolioState(1, 10.0, 0.0, TestFixtures.bar("2026-08-07T09:10"));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.SELL, 10),
                TestFixtures.bar("2026-08-07T15:20"), before, TestFixtures.ctx());

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(out.totalFilledQty()).isEqualTo(10);
        // 15:25 시점엔 취소가 전송되지 않았어야 한다 (일반 fillTimeout 미적용)
        verify(broker, never()).cancelOrder(anyString(), anyLong(), any());
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void closing_auction_order_eod_at_1536_carries_liquidation_pending() {
        // 15:20 bar 주문, 끝까지 미체결인 채 clock 15:36 도달 -> 그때에만 EOD 처리(취소->이월)
        long d = clearDecisionWithNewOrder(10);
        when(broker.submitMarketOrder("005930", OrderSide.SELL, 10))
                .thenReturn(new KisOrderAck("0001"));

        mutableClock().set(Instant.parse("2026-08-07T06:20:00Z")); // 15:20 KST

        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().set(Instant.parse("2026-08-07T06:36:00Z")); // 15:36 KST -- 데드라인 도달
                    return Optional.of(new KisOrderSnapshot("0001", 10, 0, BigDecimal.ZERO, false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot("0001", 10, 0, BigDecimal.ZERO, true, true)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("0.00")));

        var before = new PortfolioState(1, 10.0, 0.0, TestFixtures.bar("2026-08-07T09:10"));

        ChainOutcome out = lifecycle.runChain(d, new OrderPlan(OrderSide.SELL, 10),
                TestFixtures.bar("2026-08-07T15:20"), before, TestFixtures.ctx());

        OrderEntity order = orders.findTopByDecisionIdOrderByAttemptDesc(d).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(broker, times(1)).cancelOrder(anyString(), anyLong(), any());

        var snap = snapshots.findById("005930").orElseThrow();
        assertThat(snap.isLiquidationPending()).isTrue();
        assertThat(decisions.findById(d).orElseThrow().getExecutionStatus()).isEqualTo("IN_PROGRESS");
        assertThat(out.after().units()).isEqualTo(1);   // 이전 값 유지
        assertThat(alerts.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_CLEAR_EOD"));
    }
}
