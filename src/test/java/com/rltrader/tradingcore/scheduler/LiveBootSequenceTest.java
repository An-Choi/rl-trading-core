package com.rltrader.tradingcore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.kis.KisTokenManager;
import com.rltrader.tradingcore.ledger.HaltEntity;
import com.rltrader.tradingcore.ledger.HaltRepository;
import com.rltrader.tradingcore.ledger.OrderEventRepository;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.MetadataResponse;
import com.rltrader.tradingcore.predict.PredictClient;
import com.rltrader.tradingcore.reconcile.ReconciliationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Task 14 — live 기동 순서(spec §7 ①~⑩) 8개 시나리오. Testcontainers Postgres,
 * @MockitoBean BrokerPort + KisTokenManager(실 KIS 네트워크 호출 차단). predict는 WireMock
 * 대신 @MockitoBean PredictClient로 직접 스텁 — 이 테스트는 사이클 실행이 아니라 부트 게이트
 * 순서만 검증하므로 HTTP 왕복이 필요 없다.
 */
@ActiveProfiles({"test", "live"})
class LiveBootSequenceTest extends AbstractIntegrationTest {

    private static final String SYMBOL = "005930";

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-08-07T02:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired BootSequence boot;
    @Autowired PortfolioManager portfolio;
    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired OrderRepository orders;
    @Autowired OrderEventRepository orderEvents;
    @Autowired HaltRepository halts;
    @Autowired ReconciliationRepository reconciliations;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @MockitoBean BrokerPort broker;
    @MockitoBean KisTokenManager tokenManager;
    @MockitoBean PredictClient predict;

    private static MetadataResponse validMeta() {
        return new MetadataResponse("ppo-fs3-x", 3,
                Map.of("type", "discrete", "n", 3, "labels", List.of("hold", "add_unit", "clear")),
                Map.of("unit_fraction", 0.199, "initial_cash", 10_000.0, "max_units", 5),
                "sha");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM reconciliations");
        jdbc.update("DELETE FROM execution_adjustments");
        jdbc.update("DELETE FROM order_events");
        jdbc.update("DELETE FROM executions");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM decisions");
        jdbc.update("DELETE FROM halts");
        jdbc.update("DELETE FROM alerts");
        snapshots.deleteAll();
        reset(broker, tokenManager, predict);
        when(predict.ready()).thenReturn(true);
        when(predict.metadata()).thenReturn(validMeta());
        when(tokenManager.accessToken()).thenReturn("tok");
    }

    // ---------------------------------------------------------------- 픽스처 헬퍼

    private long insertDecision(String artifactId, String executionStatus) {
        return jdbc.queryForObject("""
            INSERT INTO decisions (symbol, bar_ts, action, label, action_mask, observation,
                bar_close, artifact_id, execution_status)
            VALUES (?, now(), 1, 'add_unit', '[]', '[]', 100.0, ?, ?)
            RETURNING id""", Long.class, SYMBOL, artifactId, executionStatus);
    }

    private long insertOrder(long decisionId, String status, String kisOrderNo, long qty,
            LocalDate tradingDate, OrderSide side) {
        return insertOrder(decisionId, status, kisOrderNo, qty, tradingDate, side, 0);
    }

    private long insertOrder(long decisionId, String status, String kisOrderNo, long qty,
            LocalDate tradingDate, OrderSide side, long filledQty) {
        boolean terminal = filledQty > 0;
        return jdbc.queryForObject("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, kis_order_no, filled_qty, accounted_filled_qty, accounted_fill_notional,
                avg_fill_price, terminal_at)
            VALUES (?, 1, ?, ?, ?, ?, 'MARKET', ?, ?, ?, ?, ?, ?, %s)
            RETURNING id""".formatted(terminal ? "now()" : "NULL"),
                Long.class, decisionId, tradingDate, SYMBOL, side.name(), qty, status, kisOrderNo,
                filledQty, filledQty, terminal ? new BigDecimal("100.0").multiply(BigDecimal.valueOf(filledQty)) : BigDecimal.ZERO,
                terminal ? new BigDecimal("100.0") : null);
    }

    private void seedSnapshot(String artifactId, int units, double shares, double cash,
            boolean liquidationPending) {
        PortfolioSnapshotEntity s = new PortfolioSnapshotEntity();
        s.setSymbol(SYMBOL);
        s.setUnits(units);
        s.setShares(shares);
        s.setCash(cash);
        s.setArtifactId(artifactId);
        s.setUpdatedAt(OffsetDateTime.now(clock));
        s.setLiquidationPending(liquidationPending);
        snapshots.save(s);
    }

    private void defaultBalance() {
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("10000.00")));
    }

    // ---------------------------------------------------------------- ①

    @Test
    void boot_refused_when_active_halt_exists() {
        HaltEntity h = new HaltEntity();
        h.setCode("RECON_UNEXPLAINED");
        h.setDetail("{}");
        halts.save(h);

        assertThatThrownBy(boot::prepare).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active halt");
        verifyNoInteractions(predict);   // ①에서 막히면 핸드셰이크(②③)까지도 도달하지 않는다
    }

    // ---------------------------------------------------------------- 크래시 복구(관찰 점프)

    @Test
    void crashed_order_recovered_by_observation_jump() throws Exception {
        long d = insertDecision("ppo-fs3-x", "IN_PROGRESS");
        insertOrder(d, "SUBMITTED", "K001", 10, LocalDate.now(clock), OrderSide.BUY);
        seedSnapshot("ppo-fs3-x", 0, 0.0, 10_000.0, false);

        when(broker.orderSnapshot(eq("K001"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K001", 10, 10, new BigDecimal("101.5"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("8985.00")));

        boot.prepare();

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE decision_id = ?", d);
        assertThat(order.get("status")).isEqualTo("FILLED");
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(10L);
        assertThat(jdbc.queryForObject(
                "SELECT execution_status FROM decisions WHERE id = ?", String.class, d))
                .isEqualTo("COMPLETED");
        assertThat(portfolio.current().shares()).isEqualTo(10.0);
        assertThat(halts.findByClearedAtIsNull()).isEmpty();   // 성공 경로 — HALT 없음
    }

    // ---------------------------------------------------------------- 전일 NEW 발견/미발견

    @Test
    void yesterday_new_order_discovered_is_adopted_and_finalized() throws Exception {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        long d = insertDecision("ppo-fs3-x", "IN_PROGRESS");
        insertOrder(d, "NEW", null, 10, yesterday, OrderSide.BUY);
        seedSnapshot("ppo-fs3-x", 0, 0.0, 10_000.0, false);

        when(broker.dailyOrders(eq(yesterday))).thenReturn(
                List.of(new KisOrderSnapshot("Y001", 10, 10, new BigDecimal("100.0"), true, false)));
        when(broker.orderSnapshot(eq("Y001"), eq(yesterday)))
                .thenReturn(Optional.of(new KisOrderSnapshot("Y001", 10, 10, new BigDecimal("100.0"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("9000.00")));

        boot.prepare();

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE decision_id = ?", d);
        assertThat(order.get("status")).isEqualTo("FILLED");
        assertThat(order.get("kis_order_no")).isEqualTo("Y001");
        verify(broker, never()).submitMarketOrder(any(), any(), anyLong());
        assertThat(halts.findByClearedAtIsNull()).isEmpty();
    }

    @Test
    void yesterday_new_order_not_found_halts_without_resend() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        long d = insertDecision("ppo-fs3-x", "IN_PROGRESS");
        insertOrder(d, "NEW", null, 10, yesterday, OrderSide.BUY);
        seedSnapshot("ppo-fs3-x", 0, 0.0, 10_000.0, false);

        when(broker.dailyOrders(eq(yesterday))).thenReturn(List.of());

        assertThatThrownBy(boot::prepare).isInstanceOf(IllegalStateException.class);

        verify(broker, never()).submitMarketOrder(any(), any(), anyLong());
        assertThat(halts.findByClearedAtIsNull()).anyMatch(h -> h.getCode().equals("SUBMIT_UNKNOWN"));
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE decision_id = ?", d);
        assertThat(order.get("status")).isEqualTo("NEW");   // ABORTED 단정 금지 — 비종결 유지
    }

    // ---------------------------------------------------------------- 체인 불변식

    @Test
    void chain_invariant_violation_halts_boot() {
        // IN_PROGRESS decision인데 그 주문은 전부 terminal, liquidationPending도 false —
        // 아무 것도 이 IN_PROGRESS를 설명하지 못하는 모순 상태.
        long d = insertDecision("ppo-fs3-x", "IN_PROGRESS");
        insertOrder(d, "FILLED", "K999", 10, LocalDate.now(clock), OrderSide.BUY, 10);
        seedSnapshot("ppo-fs3-x", 1, 10.0, 9000.0, false);

        assertThatThrownBy(boot::prepare).isInstanceOf(IllegalStateException.class);

        assertThat(halts.findByClearedAtIsNull()).anyMatch(h -> h.getCode().equals("RECON_UNEXPLAINED"));
    }

    // ---------------------------------------------------------------- artifact 판정

    @Test
    void artifact_mismatch_with_real_holdings_halts() {
        seedSnapshot("ppo-fs3-OLD", 1, 10.0, 9000.0, false);
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("9000.00")));

        assertThatThrownBy(boot::prepare).isInstanceOf(IllegalStateException.class);

        assertThat(halts.findByClearedAtIsNull())
                .anyMatch(h -> h.getCode().equals("ARTIFACT_POSITION_MISMATCH"));
        // flat 초기화 금지 — snapshot이 그대로 남아있어야 한다
        assertThat(snapshots.findById(SYMBOL).orElseThrow().getUnits()).isEqualTo(1);
    }

    @Test
    void artifact_mismatch_without_holdings_resets_flat() throws Exception {
        seedSnapshot("ppo-fs3-OLD", 1, 10.0, 9000.0, false);
        defaultBalance();   // holdingQty = 0

        boot.prepare();

        assertThat(portfolio.current().units()).isZero();
        assertThat(portfolio.current().shares()).isZero();
        assertThat(portfolio.current().cash()).isEqualTo(10_000.0);
        PortfolioSnapshotEntity after = snapshots.findById(SYMBOL).orElseThrow();
        assertThat(after.getUnits()).isZero();
        assertThat(after.getArtifactId()).isEqualTo("ppo-fs3-x");   // 새 artifact로 갱신
        assertThat(halts.findByClearedAtIsNull()).isEmpty();
    }

    // ---------------------------------------------------------------- 복구가 원본 위에서 실행됨

    @Test
    void recovery_runs_on_raw_snapshot_before_artifact_verdict() {
        // artifact 불일치 + 실보유 + 부분체결 open order(ACKED, KIS 전량 체결 관찰).
        seedSnapshot("ppo-fs3-OLD", 1, 5.0, 9500.0, false);
        long d = insertDecision("ppo-fs3-OLD", "IN_PROGRESS");
        insertOrder(d, "ACKED", "K777", 5, LocalDate.now(clock), OrderSide.BUY);

        when(broker.orderSnapshot(eq("K777"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K777", 5, 5, new BigDecimal("100.0"), true, false)));
        // balance는 restoreRaw 원본(5주) + 이번 체결(5주) = 10주가 실보유
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("9000.00")));

        assertThatThrownBy(boot::prepare).isInstanceOf(IllegalStateException.class);

        assertThat(halts.findByClearedAtIsNull())
                .anyMatch(h -> h.getCode().equals("ARTIFACT_POSITION_MISMATCH"));
        // 복구(⑤)가 원본(5주) 위에서 체결분(5주)을 더해 10주로 반영했는지 — flat(0) 기준으로
        // 오염됐다면 5주(체결분만)로 남았을 것이다.
        PortfolioSnapshotEntity snap = snapshots.findById(SYMBOL).orElseThrow();
        assertThat(snap.getShares()).isEqualByComparingTo("10");
        // snapshot.artifact_id가 복구 후에도 원본("ppo-fs3-OLD") 그대로인지 — RECOVERY
        // finalization이 기존 snapshot의 artifact_id를 보존해야 ⑦이 불일치를 계속 볼 수 있다.
        assertThat(snap.getArtifactId()).isEqualTo("ppo-fs3-OLD");
    }

    @Test
    void recovery_without_snapshot_uses_decision_artifact_as_provenance() {
        // snapshot 행 없음(첫 finalization 전 크래시) + 이전 artifact "a-old"의 IN_PROGRESS decision
        // + ACKED 주문(KIS 전량 체결 관찰) + 새 artifact "ppo-fs3-x"로 재기동.
        long d = insertDecision("a-old", "IN_PROGRESS");
        insertOrder(d, "ACKED", "K555", 7, LocalDate.now(clock), OrderSide.BUY);

        when(broker.orderSnapshot(eq("K555"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K555", 7, 7, new BigDecimal("100.0"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(7, new BigDecimal("9300.00")));

        assertThatThrownBy(boot::prepare).isInstanceOf(IllegalStateException.class);

        assertThat(halts.findByClearedAtIsNull())
                .anyMatch(h -> h.getCode().equals("ARTIFACT_POSITION_MISMATCH"));
        PortfolioSnapshotEntity snap = snapshots.findById(SYMBOL).orElseThrow();
        assertThat(snap.getArtifactId()).isEqualTo("a-old");   // ctx("ppo-fs3-x") 아님 — decision provenance
    }
}
