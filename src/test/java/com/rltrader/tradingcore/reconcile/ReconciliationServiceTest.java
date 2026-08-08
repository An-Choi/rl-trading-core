package com.rltrader.tradingcore.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.ledger.ExecutionAdjustmentRepository;
import com.rltrader.tradingcore.ledger.HaltRepository;
import com.rltrader.tradingcore.ledger.OrderEventRepository;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.scheduler.CycleAdmissionGate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Task 13 — 2레벨 대사(ORDER/BALANCE) + 지연 체결 adjustment 경로 + 트리거 게이팅.
 * 브리프의 8개 시나리오. Testcontainers Postgres, @MockitoBean BrokerPort.
 */
@SpringBootTest
@TestPropertySource(properties = "order.fill-poll-interval-ms=0")
class ReconciliationServiceTest extends AbstractIntegrationTest {

    private static final String SYMBOL = "005930";
    private static final ArtifactContext CTX = new ArtifactContext("ppo-fs3-x", 3, 0.199, 10_000.0, 5);

    @Autowired ReconciliationService recon;
    @Autowired ReconciliationRepository reconciliations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PortfolioManager portfolio;
    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired ExecutionAdjustmentRepository adjustments;
    @Autowired OrderEventRepository orderEvents;
    @Autowired HaltRepository halts;
    @Autowired CycleAdmissionGate gate;
    @MockitoBean BrokerPort broker;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM reconciliations");
        jdbc.update("DELETE FROM execution_adjustments");
        jdbc.update("DELETE FROM order_events");
        jdbc.update("DELETE FROM executions");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM decisions");
        jdbc.update("DELETE FROM halts");
        snapshots.deleteAll();
        reset(broker);
        // 잠긴 채로 남는 테스트가 없어야 하지만 방어적으로 항상 release 가능한 상태로 시작
        if (!gate.tryAcquire()) {
            fail("gate should be free at test start");
        }
        gate.release();
    }

    private static void fail(String msg) {
        throw new IllegalStateException(msg);
    }

    // ---------------------------------------------------------------- 픽스처 헬퍼

    private long insertDecision() {
        return jdbc.queryForObject("""
            INSERT INTO decisions (symbol, bar_ts, action, label, action_mask, observation,
                bar_close, artifact_id, execution_status)
            VALUES (?, now(), 1, 'add_unit', '[]', '[]', 100.0, ?, 'COMPLETED')
            RETURNING id""", Long.class, SYMBOL, CTX.artifactId());
    }

    /** 비종결(ACKED) 주문 — ghost fill 시나리오용, filled 0. */
    private long insertOpenOrder(long decisionId, String kisOrderNo, long qty) {
        return jdbc.queryForObject("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, kis_order_no, filled_qty, accounted_filled_qty, accounted_fill_notional)
            VALUES (?, 1, CURRENT_DATE, ?, 'BUY', ?, 'MARKET', 'ACKED', ?, 0, 0, 0)
            RETURNING id""", Long.class, decisionId, SYMBOL, qty, kisOrderNo);
    }

    /** CANCELLED 주문 — accounted qty/notional 지정, filled_qty=accounted(부분 체결로 종결). */
    private long insertCancelledOrder(long decisionId, String kisOrderNo, long qty,
            long accountedQty, BigDecimal accountedNotional) {
        return jdbc.queryForObject("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, kis_order_no, filled_qty, accounted_filled_qty, accounted_fill_notional, terminal_at)
            VALUES (?, 1, CURRENT_DATE, ?, 'BUY', ?, 'MARKET', 'CANCELLED', ?, ?, ?, ?, now())
            RETURNING id""", Long.class, decisionId, SYMBOL, qty, kisOrderNo, accountedQty,
                accountedQty, accountedNotional);
    }

    private void insertExecution(long decisionId, long orderId, double fillPrice, double sharesDelta,
            int unitsAfter, double sharesAfter, double cashAfter) {
        jdbc.update("""
            INSERT INTO executions (decision_id, order_id, fill_price, shares_delta,
                units_after, shares_after, cash_after) VALUES (?, ?, ?, ?, ?, ?, ?)""",
                decisionId, orderId, fillPrice, sharesDelta, unitsAfter, sharesAfter, cashAfter);
    }

    private void seedSnapshot(int units, double shares, double cash) {
        PortfolioSnapshotEntity s = new PortfolioSnapshotEntity();
        s.setSymbol(SYMBOL);
        s.setUnits(units);
        s.setShares(shares);
        s.setCash(cash);
        s.setArtifactId(CTX.artifactId());
        s.setUpdatedAt(OffsetDateTime.now());
        s.setLiquidationPending(false);
        snapshots.save(s);
        portfolio.apply(new PortfolioState(units, shares, cash, null));
    }

    private List<Map<String, Object>> reconRows(String level) {
        return jdbc.queryForList("SELECT * FROM reconciliations WHERE level = ? ORDER BY id", level);
    }

    // ---------------------------------------------------------------- 시나리오 1

    @Test
    void ghost_fill_on_open_order_is_finalized_via_shared_path() {
        long d = insertDecision();
        long orderId = insertOpenOrder(d, "K001", 10);
        seedSnapshot(0, 0.0, 10_000.0);

        when(broker.orderSnapshot(eq("K001"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K001", 10, 10, new BigDecimal("101.5"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("8985.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("FILLED");
        assertThat(((Number) order.get("filled_qty")).longValue()).isEqualTo(10L);
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(10L);
        Long execCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE order_id = ?", Long.class, orderId);
        assertThat(execCount).isEqualTo(1L);
        assertThat(portfolio.current().shares()).isEqualTo(10.0);
        assertThat(portfolio.current().cash()).isEqualTo(8985.00);
        assertThat(snapshots.findById(SYMBOL).orElseThrow().getShares()).isEqualByComparingTo("10");

        assertThat(reconRows("ORDER")).hasSize(1);
        assertThat(reconRows("ORDER").get(0).get("result")).isEqualTo("SYNCED");
    }

    // ---------------------------------------------------------------- 시나리오 2

    @Test
    void late_fill_after_cancel_adjusts_idempotently() {
        long d = insertDecision();
        // CANCELLED, accounted 5주@100 = 500. KIS 누적 7주 avg 103 → new 2주, toNotional=7*103=721,
        // notionalDelta = 721 - 500 = 221 (누적 차분 — 브리프 "221·110.5" 케이스, per-share 110.5*2=221).
        long orderId = insertCancelledOrder(d, "K002", 10, 5, new BigDecimal("500.0000"));
        insertExecution(d, orderId, 100.0, 5.0, 0, 5.0, 500.0);
        seedSnapshot(0, 5.0, 10_000.0);

        when(broker.orderSnapshot(eq("K002"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K002", 10, 7, new BigDecimal("103"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(7, new BigDecimal("9500.00")));

        recon.reconcile(Trigger.PERIODIC);

        List<Map<String, Object>> adjRows = jdbc.queryForList(
                "SELECT * FROM execution_adjustments WHERE order_id = ?", orderId);
        assertThat(adjRows).hasSize(1);
        Map<String, Object> adj = adjRows.get(0);
        assertThat(((Number) adj.get("from_filled_qty")).longValue()).isEqualTo(5L);
        assertThat(((Number) adj.get("to_filled_qty")).longValue()).isEqualTo(7L);
        assertThat(((Number) adj.get("qty_delta")).longValue()).isEqualTo(2L);
        assertThat((BigDecimal) adj.get("notional_delta")).isEqualByComparingTo("221.0000");
        assertThat((BigDecimal) adj.get("to_fill_notional")).isEqualByComparingTo("721.0000");

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("CANCELLED");   // 7 < qty(10) → CANCELLED 유지
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(7L);
        assertThat(snapshots.findById(SYMBOL).orElseThrow().getShares()).isEqualByComparingTo("7");
        assertThat(portfolio.current().shares()).isEqualTo(7.0);

        // 같은 스냅숏으로 재실행 → delta 0, adjustment 1행 유지, snapshot shares 불변
        recon.reconcile(Trigger.PERIODIC);
        List<Map<String, Object>> adjRowsAfterRerun = jdbc.queryForList(
                "SELECT * FROM execution_adjustments WHERE order_id = ?", orderId);
        assertThat(adjRowsAfterRerun).hasSize(1);
        assertThat(snapshots.findById(SYMBOL).orElseThrow().getShares()).isEqualByComparingTo("7");
    }

    @Test
    void late_fill_reaching_full_qty_corrects_to_filled() {
        long d = insertDecision();
        long orderId = insertCancelledOrder(d, "K003", 10, 5, new BigDecimal("500.0000"));
        insertExecution(d, orderId, 100.0, 5.0, 0, 5.0, 500.0);
        seedSnapshot(0, 5.0, 10_000.0);

        // 누적 10 == qty(10) → 정정 후 FILLED로 correctCancelledToFilled 경로
        when(broker.orderSnapshot(eq("K003"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K003", 10, 10, new BigDecimal("102"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("9000.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("FILLED");
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(10L);

        var events = orderEvents.findByOrderIdOrderByIdAsc(orderId);
        assertThat(events).anySatisfy(ev -> {
            assertThat(ev.getFromStatus()).isEqualTo("CANCELLED");
            assertThat(ev.getToStatus()).isEqualTo("FILLED");
        });
        // execution_adjustments가 이 정정의 근거 — 새 executions 행이 중복 생성되지 않는다
        Long execCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE order_id = ?", Long.class, orderId);
        assertThat(execCount).isEqualTo(1L);   // 최초 finalize 때 생긴 1건 그대로, adjustment는 추가 안 함
    }

    // ---------------------------------------------------------------- 시나리오 3

    @Test
    void unexplained_mismatch_halts() {
        // 로컬 shares 10, KIS holding 0, 비종결 주문 없음, 원장(=executions)도 10 → 설명 불가
        long d = insertDecision();
        long orderId = insertCancelledOrder(d, "K004", 10, 10, new BigDecimal("1000.0000"));
        insertExecution(d, orderId, 100.0, 10.0, 1, 10.0, 9000.0);
        seedSnapshot(1, 10.0, 9000.0);

        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("9000.00")));

        recon.reconcile(Trigger.PERIODIC);

        assertThat(halts.findByClearedAtIsNull()).anyMatch(h -> h.getCode().equals("RECON_UNEXPLAINED"));
        assertThat(reconRows("BALANCE")).anySatisfy(row ->
                assertThat(row.get("result")).isEqualTo("UNEXPLAINED"));
    }

    // ---------------------------------------------------------------- 시나리오 4

    @Test
    void balance_check_uses_ledger_view_not_executions_only() {
        // executions 5주 + adjustment 2주, 로컬 shares 7 → CLEAN (view 기준)
        long d = insertDecision();
        long orderId = insertCancelledOrder(d, "K005", 10, 7, new BigDecimal("721.0000"));
        insertExecution(d, orderId, 100.0, 5.0, 0, 5.0, 500.0);
        jdbc.update("""
            INSERT INTO execution_adjustments (order_id, from_filled_qty, to_filled_qty,
                from_fill_notional, to_fill_notional, qty_delta, notional_delta, reason)
            VALUES (?, 5, 7, 500, 721, 2, 221, 'late-fill')""", orderId);
        seedSnapshot(0, 7.0, 9279.0);

        when(broker.balance()).thenReturn(new KisBalance(7, new BigDecimal("9279.00")));

        recon.reconcile(Trigger.PERIODIC);

        assertThat(reconRows("BALANCE")).anySatisfy(row ->
                assertThat(row.get("result")).isEqualTo("CLEAN"));
        assertThat(halts.findByClearedAtIsNull()).isEmpty();
    }

    // ---------------------------------------------------------------- 시나리오 5

    @Test
    void periodic_skips_while_cycle_in_flight() throws InterruptedException {
        seedSnapshot(0, 0.0, 10_000.0);
        // ReentrantLock은 재진입 허용이라 같은 스레드에서 tryAcquire→reconcile을 호출하면 gate가
        // 실제로 경합하지 않는다 — 다른 스레드가 점유한 상태를 재현해야 진짜 skip 경로를 검증한다.
        assertThat(gate.tryAcquire()).isTrue();
        try {
            Thread other = new Thread(() -> recon.reconcile(Trigger.PERIODIC));
            other.start();
            other.join(5_000);
            assertThat(other.isAlive()).isFalse();
        } finally {
            gate.release();
        }

        assertThat(reconciliations.findAll()).isEmpty();
        verifyNoInteractions(broker);
    }

    // ---------------------------------------------------------------- 시나리오 6

    @Test
    void cash_mismatch_syncs_snapshot_and_memory_from_balance() {
        seedSnapshot(0, 0.0, 10_000.0);   // shares 0 == ledger 0 == kis 0 (일치)

        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("9950.00")));

        recon.reconcile(Trigger.PERIODIC);

        assertThat(portfolio.current().cash()).isEqualTo(9950.00);
        assertThat(snapshots.findById(SYMBOL).orElseThrow().getCash()).isEqualByComparingTo("9950.00");
        assertThat(reconRows("BALANCE")).anySatisfy(row ->
                assertThat(row.get("result")).isEqualTo("SYNCED"));
    }

    // ---------------------------------------------------------------- 시나리오 7 (브리프 추가 케이스)

    @Test
    void adjustment_commit_refreshes_memory() {
        long d = insertDecision();
        long orderId = insertCancelledOrder(d, "K006", 10, 5, new BigDecimal("500.0000"));
        insertExecution(d, orderId, 100.0, 5.0, 0, 5.0, 500.0);
        seedSnapshot(0, 5.0, 9500.0);

        when(broker.orderSnapshot(eq("K006"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K006", 10, 7, new BigDecimal("103"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(7, new BigDecimal("9500.00")));

        recon.reconcile(Trigger.PERIODIC);

        BigDecimal ledgerSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty_delta),0) FROM accounting_ledger WHERE order_id = ?",
                BigDecimal.class, orderId);
        assertThat(BigDecimal.valueOf(portfolio.current().shares())).isEqualByComparingTo(ledgerSum);
    }

    // ---------------------------------------------------------------- 시나리오 8 — orchestrator 경유는
    // LiveCycleIntegrationTest 쪽에 별도로 추가한다 (POST_ORDER 배선, 이 클래스는 서비스 유닛 범위).
}
