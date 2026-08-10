package com.rltrader.tradingcore.cycle;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisOrderAck;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.kis.KisTransportException;
import com.rltrader.tradingcore.ledger.AlertRepository;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.ExecutionRepository;
import com.rltrader.tradingcore.ledger.OrderEventRepository;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.reconcile.ReconciliationRepository;
import com.rltrader.tradingcore.scheduler.DecisionScheduler;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Task 12 — live 사이클(LiveCycleOrchestrator + KisOrderExecutor) 통합 테스트.
 * predict stub은 DecisionCycleIntegrationTest와 동일한 WireMock 픽스처 형식을 재사용한다.
 * order.recon-interval-ms를 크게 잡아 PeriodicReconciliationTrigger(Task 13, live 프로파일
 * @Scheduled)가 실제 벽시계 기준으로 테스트 도중 끼어들지 않게 한다 — 이 클래스는 대사를 항상
 * cycle.runCycle()이 유발하는 POST_ORDER 경로로만 검증한다(order_timeout_triggers_post_order_reconcile).
 */
@ActiveProfiles({"test", "live"})
@TestPropertySource(properties = {"order.fill-poll-interval-ms=0", "order.recon-interval-ms=3600000"})
@Import(LiveCycleIntegrationTest.TestClockConfig.class)
class LiveCycleIntegrationTest extends AbstractIntegrationTest {

    /**
     * scheduler.tick()이 grid 발화로 판정할 고정 KST 평일 장중 시각 — 실제 벽시계와 무관하게 결정적.
     * Task 13: order_timeout_triggers_post_order_reconcile이 fill-timeout(60s 데드라인 도달)을
     * 시뮬레이션해야 하므로 Clock.fixed 대신 advance() 가능한 뮤터블 클록 — OrderLifecycleCancelRetryTest
     * (execution 패키지, package-private TestMutableClock)와 동일한 패턴을 cycle 패키지에 맞게 재구현.
     * 다른 테스트는 advance()를 호출하지 않으므로 기존 결정적 동작에 영향 없다.
     */
    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock(Instant.parse("2026-08-07T02:00:00Z"),   // 2026-08-07(금) 11:00 KST
                    com.rltrader.tradingcore.domain.TradingGrid.KST);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(Instant start, ZoneId zone) {
            this.now = new AtomicReference<>(start);
            this.zone = zone;
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static WireMockServer wiremock = new WireMockServer(WireMockConfiguration.options().port(18098));

    @DynamicPropertySource
    static void predictUrl(DynamicPropertyRegistry registry) {
        registry.add("trading.predict-base-url", () -> "http://127.0.0.1:18098");
        registry.add("trading.read-timeout-ms", () -> 2000);
    }

    @BeforeAll static void startWm() { wiremock.start(); }
    @AfterAll static void stopWm() { wiremock.stop(); }

    @Autowired DecisionCycleService cycle;
    @Autowired DecisionScheduler scheduler;
    @Autowired PortfolioManager portfolio;
    @Autowired DecisionRepository decisions;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @Autowired OrderEventRepository orderEvents;
    @Autowired AlertRepository alerts;
    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired ReconciliationRepository reconciliations;
    @MockitoBean BrokerPort broker;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    static final ZonedDateTime BAR =
            ZonedDateTime.parse("2026-08-07T11:00:00+09:00[Asia/Seoul]");
    static final ArtifactContext CTX = new ArtifactContext("ppo-fs3-x", 3, 0.199, 10_000.0, 5);

    // bar_close는 100.0 — OrderPlanner.plan은 수량을 floor(notional/barClose)로 내림한다
    // (spec §3, 소수 shares를 허용하는 DryRunExecutor와 다름). notional=1990(=10000*0.199)일 때
    // 71000처럼 큰 bar_close를 쓰면 qty=0이 되어 ck_orders_qty(CHECK qty>0)를 위반한다 —
    // 100.0이면 qty=floor(1990/100)=19로 실주문 가능한 수량이 나온다.
    private static String okBody(int action, String label, String barTs) {
        return """
            {"action": %d, "label": "%s", "action_mask": [true, true, true],
             "bar_ts": "%s", "artifact_id": "ppo-fs3-x", "feature_schema_version": 3,
             "observation": [0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,0.0,0.0,0.0,0.5],
             "bar_close": 100.0}""".formatted(action, label, barTs);
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
        orderEvents.deleteAll();
        executions.deleteAll();
        orders.deleteAll();
        decisions.deleteAll();
        alerts.deleteAll();
        snapshots.deleteAll();
        reconciliations.deleteAll();
        portfolio.apply(PortfolioState.flat(10_000.0));
        cycle.initialize(CTX);
        Mockito.reset(broker);
        mutableClock().advance(Duration.between(mutableClock().instant(),
                Instant.parse("2026-08-07T02:00:00Z")));   // 매 테스트 시작 시 기준 시각으로 재정렬
    }

    @Test
    void add_cycle_commits_tx1_before_broker_call() throws Exception {
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-07T11:00:00+09:00"))));

        CountDownLatch enteredBroker = new CountDownLatch(1);
        CountDownLatch releaseBroker = new CountDownLatch(1);
        when(broker.submitMarketOrder(eq("005930"), eq(OrderSide.BUY), anyLong()))
                .thenAnswer(inv -> {
                    enteredBroker.countDown();
                    releaseBroker.await();
                    return new KisOrderAck("0001", "05360");
                });
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot(
                        "0001", 19, 19, new BigDecimal("100.0"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(19, new BigDecimal("8100.00")));

        Thread cycleThread = new Thread(() -> cycle.runCycle(BAR, null));
        cycleThread.start();
        try {
            assertThat(enteredBroker.await(5, TimeUnit.SECONDS)).isTrue();

            // tx1(decisions IN_PROGRESS + orders NEW)이 broker 호출 이전에 이미 커밋돼 있어야 한다
            // — 별도 JdbcTemplate 조회로 확인("기록 먼저, 제출 나중", spec §2)
            List<java.util.Map<String, Object>> committedDecisions =
                    jdbc.queryForList("select * from decisions where symbol = '005930'");
            assertThat(committedDecisions).hasSize(1);
            assertThat(committedDecisions.get(0).get("execution_status")).isEqualTo("IN_PROGRESS");

            List<java.util.Map<String, Object>> committedOrders =
                    jdbc.queryForList("select * from orders where symbol = '005930'");
            assertThat(committedOrders).hasSize(1);
            assertThat(committedOrders.get(0).get("status")).isEqualTo("NEW");
        } finally {
            releaseBroker.countDown();
            cycleThread.join(10_000);
        }

        assertThat(orders.findAll()).anySatisfy(o ->
                assertThat(o.getStatus()).isEqualTo(OrderStatus.FILLED));
        assertThat(decisions.findAll()).anySatisfy(d ->
                assertThat(d.getExecutionStatus()).isEqualTo("COMPLETED"));
        assertThat(portfolio.current().units()).isEqualTo(1);
    }

    @Test
    void hold_cycle_uses_single_tx_and_marks_completed() {
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(0, "hold", "2026-08-07T11:00:00+09:00"))));

        CycleOutcome outcome = cycle.runCycle(BAR, null);

        assertThat(outcome).isEqualTo(CycleOutcome.EXECUTED);
        assertThat(orders.findAll()).isEmpty();
        assertThat(decisions.findAll()).hasSize(1);
        assertThat(decisions.findAll().get(0).getExecutionStatus()).isEqualTo("COMPLETED");
        verifyNoInteractions(broker);
        assertThat(portfolio.current().units()).isZero();
    }

    @Test
    void chain_incomplete_alerts_and_leaves_memory_unchanged() {
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-07T11:00:00+09:00"))));

        when(broker.submitMarketOrder(eq("005930"), eq(OrderSide.BUY), anyLong()))
                .thenReturn(new KisOrderAck("0001", "05360"));
        // 폴링: terminal 후보(전량 체결) 반환, 그 다음 확정 조회(confirm)가 전송 계층 실패
        when(broker.orderSnapshot(eq("0001"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot(
                        "0001", 19, 19, new BigDecimal("100.0"), true, false)))
                .thenThrow(new KisTransportException("read timeout", true, null));

        CycleOutcome outcome = cycle.runCycle(BAR, null);

        assertThat(outcome).isEqualTo(CycleOutcome.NOOP_ALERTED);
        assertThat(portfolio.current().units()).isZero();          // 메모리 무변경
        assertThat(portfolio.current().shares()).isZero();
        assertThat(alerts.findAll()).anyMatch(a -> a.getCode().equals("CHAIN_INCOMPLETE"));
        assertThat(orders.findAll()).hasSize(1);
        assertThat(orders.findAll().get(0).getStatus().isTerminal()).isFalse();   // 비종결 유지
        assertThat(decisions.findAll().get(0).getExecutionStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void pending_order_skips_next_grid() {
        // 비종결 주문을 직접 심어둔다 (decision + NEW order)
        long decisionId = jdbc.queryForObject("""
            INSERT INTO decisions (symbol, bar_ts, action, label, action_mask, observation,
                bar_close, artifact_id, execution_status)
            VALUES ('005930', now(), 1, 'add_unit', '[]', '[]', 100.0, 'ppo-fs3-x', 'IN_PROGRESS')
            RETURNING id""", Long.class);
        jdbc.update("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, filled_qty, accounted_filled_qty, accounted_fill_notional)
            VALUES (?, 1, CURRENT_DATE, '005930', 'BUY', 10, 'MARKET', 'ACKED', 0, 0, 0)""",
                decisionId);

        scheduler.enable();
        scheduler.tick();

        assertThat(alerts.findAll()).anyMatch(a -> a.getCode().equals("SKIPPED_ORDER_PENDING"));
        wiremock.verify(0, postRequestedFor(urlEqualTo("/predict")));
    }

    @Test
    void liquidation_pending_resumes_before_predict() {
        var snap = new com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity();
        snap.setSymbol("005930");
        snap.setUnits(1);
        snap.setShares(6.0);
        snap.setCash(400.00);
        snap.setArtifactId(CTX.artifactId());
        snap.setUpdatedAt(java.time.OffsetDateTime.now());
        snap.setLiquidationPending(true);
        snapshots.save(snap);

        long decisionId = jdbc.queryForObject("""
            INSERT INTO decisions (symbol, bar_ts, action, label, action_mask, observation,
                bar_close, artifact_id, execution_status)
            VALUES ('005930', now(), 2, 'clear', '[]', '[]', 100.0, 'ppo-fs3-x', 'IN_PROGRESS')
            RETURNING id""", Long.class);
        jdbc.update("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, filled_qty, accounted_filled_qty, accounted_fill_notional, kis_order_no)
            VALUES (?, 1, CURRENT_DATE, '005930', 'SELL', 6, 'MARKET', 'CANCELLED', 6, 6, 600.0, '0001')""",
                decisionId);

        portfolio.apply(new PortfolioState(1, 6.0, 400.00, BAR.minusMinutes(30)));

        when(broker.submitMarketOrder(eq("005930"), eq(OrderSide.SELL), anyLong()))
                .thenReturn(new KisOrderAck("0002", "05360"));
        when(broker.orderSnapshot(eq("0002"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot(
                        "0002", 6, 6, new BigDecimal("99.0"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("994.00")));

        scheduler.enable();
        scheduler.tick();

        wiremock.verify(0, postRequestedFor(urlEqualTo("/predict")));
        verify(broker).submitMarketOrder(eq("005930"), eq(OrderSide.SELL), eq(6L));
        assertThat(snapshots.findById("005930").orElseThrow().isLiquidationPending()).isFalse();
    }

    @Test
    void order_timeout_triggers_post_order_reconcile() {
        // Task 13 통합 케이스 — attempt1이 fill-timeout(60s 데드라인 도달, 부분 3주)으로 취소되고
        // attempt2가 잔량 16주를 전량 체결해 체인이 끝난다: ChainOutcome.hadTimeout=true가
        // LiveCycleOrchestrator를 거쳐 reconcile(POST_ORDER) 1회 호출로 이어져야 한다
        // (OrderLifecycleCancelRetryTest.timeout_cancels_then_retries_once_in_same_finalization_tx와
        // 동일 타임아웃 시뮬레이션 패턴 — MutableClock.advance()로 실시간 대기 없이 데드라인 도달).
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-07T11:00:00+09:00"))));   // qty=floor(1990/100)=19

        when(broker.submitMarketOrder(eq("005930"), eq(OrderSide.BUY), eq(19L)))
                .thenReturn(new KisOrderAck("T001", "05360"));
        when(broker.submitMarketOrder(eq("005930"), eq(OrderSide.BUY), eq(16L)))
                .thenReturn(new KisOrderAck("T002", "05360"));
        // attempt1: 폴링 1회차 부분(3주) → clock 61s 전진 → 데드라인 도달 → 취소 흐름
        when(broker.orderSnapshot(eq("T001"), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    mutableClock().advance(Duration.ofSeconds(61));
                    return Optional.of(new KisOrderSnapshot(
                            "T001", 19, 3, new BigDecimal("100.0"), false, false));
                })
                .thenReturn(Optional.of(new KisOrderSnapshot(
                        "T001", 19, 3, new BigDecimal("100.0"), true, true)));   // 취소 확정
        // attempt2: 잔량 16주 전량 체결
        when(broker.orderSnapshot(eq("T002"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot(
                        "T002", 16, 16, new BigDecimal("100.0"), true, false)));
        // POST_ORDER 대사가 체인 완료 직후 이어서 부르는 order-level 스캔: 두 attempt 모두 이미
        // 종결 상태이므로 non-terminal 스캔은 빈 목록, CANCELLED(attempt1) 재조회는
        // accounted(3)==canonical(3)이라 adjustment 없음 — 세 번째 balance() 호출은
        // reconcile(POST_ORDER)의 balance-level 몫이다.
        when(broker.balance())
                .thenReturn(new KisBalance(3, new BigDecimal("9700.00")))    // attempt1 finalization
                .thenReturn(new KisBalance(19, new BigDecimal("8100.00")))   // attempt2 finalization
                .thenReturn(new KisBalance(19, new BigDecimal("8100.00")));  // reconcile(POST_ORDER)

        CycleOutcome outcome = cycle.runCycle(BAR, null);

        assertThat(outcome).isEqualTo(CycleOutcome.EXECUTED);
        assertThat(orders.findAll()).anySatisfy(o ->
                assertThat(o.getStatus()).isEqualTo(OrderStatus.FILLED));
        assertThat(reconciliations.findAll()).anyMatch(r -> r.getTriggerSource().equals("POST_ORDER"));
    }
}
