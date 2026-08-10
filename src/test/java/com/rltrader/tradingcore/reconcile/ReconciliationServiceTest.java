package com.rltrader.tradingcore.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.ledger.AlertRepository;
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
import java.time.ZonedDateTime;
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
    @Autowired AlertRepository alertRepo;
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
        jdbc.update("DELETE FROM alerts");
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

    /** NEW 주문(kis_order_no 없음) — 부팅 orphan 해소(resolveOrphanedOrders) 시나리오용. */
    private long insertOrphanOrder(long decisionId, long qty) {
        return jdbc.queryForObject("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, filled_qty, accounted_filled_qty, accounted_fill_notional)
            VALUES (?, 1, CURRENT_DATE, ?, 'BUY', ?, 'MARKET', 'NEW', 0, 0, 0)
            RETURNING id""", Long.class, decisionId, SYMBOL, qty);
    }

    /** 비종결(ACKED) SELL 주문 — 이월(liquidation) 시나리오용, filled 0. */
    private long insertOpenSellOrder(long decisionId, String kisOrderNo, long qty) {
        return jdbc.queryForObject("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, kis_order_no, filled_qty, accounted_filled_qty, accounted_fill_notional)
            VALUES (?, 1, CURRENT_DATE, ?, 'SELL', ?, 'MARKET', 'ACKED', ?, 0, 0, 0)
            RETURNING id""", Long.class, decisionId, SYMBOL, qty, kisOrderNo);
    }

    /** CANCELLED 주문 — accounted qty/notional 지정, filled_qty=accounted(부분 체결로 종결). */
    private long insertCancelledOrder(long decisionId, String kisOrderNo, long qty,
            long accountedQty, BigDecimal accountedNotional) {
        return insertCancelledOrder(decisionId, kisOrderNo, qty, accountedQty, accountedNotional, "BUY");
    }

    private long insertCancelledOrder(long decisionId, String kisOrderNo, long qty,
            long accountedQty, BigDecimal accountedNotional, String side) {
        return jdbc.queryForObject("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, kis_order_no, filled_qty, accounted_filled_qty, accounted_fill_notional, terminal_at)
            VALUES (?, 1, CURRENT_DATE, ?, ?, ?, 'MARKET', 'CANCELLED', ?, ?, ?, ?, now())
            RETURNING id""", Long.class, decisionId, SYMBOL, side, qty, kisOrderNo, accountedQty,
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
        seedSnapshot(units, shares, cash, null);
    }

    /**
     * entryBarTs를 실제로 세팅 가능한 변형 — SELL 체인 종료(chain-end)의 units=0/entryBarTs=null
     * "무조건 리셋" 회귀 테스트는 리셋 전 상태가 0/null이 아니어야 리셋이 실제로 일어났음을
     * 증명한다(round 3 리뷰 수정 covering test).
     */
    private void seedSnapshot(int units, double shares, double cash, ZonedDateTime entryBarTs) {
        seedSnapshot(units, shares, cash, entryBarTs, false);
    }

    /** Fix 2 covering tests — 이월(liquidation_pending=true) 스냅숏을 직접 구성. */
    private void seedSnapshot(int units, double shares, double cash, ZonedDateTime entryBarTs,
            boolean liquidationPending) {
        PortfolioSnapshotEntity s = new PortfolioSnapshotEntity();
        s.setSymbol(SYMBOL);
        s.setUnits(units);
        s.setShares(shares);
        s.setCash(cash);
        s.setArtifactId(CTX.artifactId());
        s.setEntryBarTs(entryBarTs != null ? entryBarTs.toOffsetDateTime() : null);
        s.setUpdatedAt(OffsetDateTime.now());
        s.setLiquidationPending(liquidationPending);
        snapshots.save(s);
        portfolio.apply(new PortfolioState(units, shares, cash, entryBarTs));
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

    /**
     * Fix A(2차 리뷰, P1) — 부팅 시 orphan(kis_order_no 없는 NEW) 주문이 dailyOrders 차집합으로
     * 채택되면, kis_order_no뿐 아니라 그 스냅숏이 담고 있는 KRX_FWDG_ORD_ORGNO도 함께 저장돼야
     * 한다 — 제출 ack가 없었던 경로이므로 이 조회가 org no의 유일한 출처다. 채택 후 메인 루프가
     * 이어서 관찰 점프(FINALIZED_FILLED)로 마무리하는 것도 함께 검증한다.
     */
    @Test
    void orphaned_order_adoption_at_boot_persists_org_no() {
        long d = insertDecision();
        long orderId = insertOrphanOrder(d, 10);
        seedSnapshot(0, 0.0, 10_000.0);

        LocalDate today = LocalDate.now();
        when(broker.dailyOrders(today)).thenReturn(List.of(
                new KisOrderSnapshot("K999", 10, 10, new BigDecimal("101.5"), true, false, "05360")));
        when(broker.orderSnapshot(eq("K999"), any(LocalDate.class)))
                .thenReturn(Optional.of(
                        new KisOrderSnapshot("K999", 10, 10, new BigDecimal("101.5"), true, false, "05360")));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("8985.00")));

        recon.reconcile(Trigger.STARTUP);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("kis_order_no")).isEqualTo("K999");
        assertThat(order.get("krx_fwdg_ord_orgno")).isEqualTo("05360");
        assertThat(order.get("status")).isEqualTo("FILLED");   // 채택 후 메인 루프가 관찰 점프로 마무리
    }

    /**
     * Fix A(2차 리뷰, P1) — 대사 경로(requestCancel)의 취소 시점 가드. 비종결 관찰(잔량 있음,
     * brokerTerminal=false)이 취소 요청으로 수렴하려는데 org no가 없으면(null) 공백을 전송하지
     * 않는다 — HALT(CANCEL_ORGNO_MISSING) + cancelOrder POST never called, 주문은 비종결로 남는다.
     */
    @Test
    void requestCancel_without_org_no_halts_instead_of_sending_blank() {
        long d = insertDecision();
        long orderId = insertOpenOrder(d, "K002", 10);   // org no 미저장(null) — insertOpenOrder는 세팅 안 함
        seedSnapshot(0, 0.0, 10_000.0);

        // 미체결 잔존(brokerTerminal=false) — requestCancel 분기로 수렴.
        when(broker.orderSnapshot(eq("K002"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K002", 10, 4, new BigDecimal("100.0"), false, false)));
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("10000.00")));

        recon.reconcile(Trigger.PERIODIC);

        verify(broker, never()).cancelOrder(anyString(), anyLong(), any());
        assertThat(halts.findByClearedAtIsNull())
                .anySatisfy(h -> assertThat(h.getCode()).isEqualTo("CANCEL_ORGNO_MISSING"));

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("ACKED");   // 비종결 유지 — cancel 시도 자체가 없었음
    }

    /**
     * 리뷰 수정 케이스 — 브리프 8개 시나리오는 아니지만 코드 리뷰에서 발견된 회귀 방어: SELL
     * 주문이 브로커 관점에서 취소 확정됐는데 잔량이 남아있으면(부분 체결) 정상 취소 경로
     * (OrderLifecycleService)와 동일하게 이월(liquidation)로 커밋해야 한다. finalizeObservedTerminalRemainder가
     * 무조건 ChainNext.markCompleted()로 확정하면 decisions.execution_status가 COMPLETED로
     * 굳어지고 liquidation_pending도 false로 남아 LiquidationService.resume()의 유일한 스캐너가
     * 이 잔량을 영구히 찾지 못한다.
     */
    @Test
    void observed_cancel_with_sell_remainder_carries_over_as_liquidation() {
        long d = insertDecision();
        // 이 시나리오의 decision은 아직 진행 중인 SELL 체인이다(insertDecision()의 COMPLETED
        // 기본값은 이미 종결된 다른 시나리오용) — finalizeChain은 next.completed()==false일 때
        // execution_status를 건드리지 않으므로, 대사 전 상태를 실제 미완료 체인과 맞춰야 한다.
        jdbc.update("UPDATE decisions SET execution_status = 'IN_PROGRESS' WHERE id = ?", d);
        long orderId = insertOpenSellOrder(d, "K001S", 10);
        seedSnapshot(0, 10.0, 9_000.0);

        // 브로커가 6주만 체결하고 잔량 4주를 남긴 채 취소 확정을 보고 — SELL 부분 체결.
        when(broker.orderSnapshot(eq("K001S"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K001S", 10, 6, new BigDecimal("100.0"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(4, new BigDecimal("9600.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(6L);

        assertThat(snapshots.findById(SYMBOL).orElseThrow().isLiquidationPending()).isTrue();

        Map<String, Object> decision = jdbc.queryForMap(
                "SELECT execution_status FROM decisions WHERE id = ?", d);
        assertThat(decision.get("execution_status")).isEqualTo("IN_PROGRESS");
    }

    /**
     * Critical 리뷰 수정 — BUY + 잔량 있음: 정상 경로(OrderLifecycleService)는 재시도 상한 도달 시
     * add를 포기하고 체인을 완료(markCompleted) + decisionTotalQty 기준 PARTIAL_UNIT/EXECUTION_FAILED
     * 알림을 남긴다. 대사가 이를 관찰해도 같은 시맨틱을 재현해야 한다 — decisionTotalQty는
     * OrderRepository.sumAccountedFilledQtyByDecisionId 재사용(브리프 지시), 여기서는 이 order가
     * 이 decision의 유일한 attempt이므로 canonical filled(7)가 곧 decisionTotalQty.
     */
    @Test
    void observed_cancel_with_buy_remainder_completes_decision_with_partial_unit_alert() {
        long d = insertDecision();
        jdbc.update("UPDATE decisions SET execution_status = 'IN_PROGRESS' WHERE id = ?", d);
        long orderId = insertOpenOrder(d, "K001B", 10);
        seedSnapshot(0, 0.0, 10_000.0);

        // 브로커가 7주만 체결하고 잔량 3주를 남긴 채 취소 확정 — BUY 부분 체결(재시도 상한 도달 관찰).
        when(broker.orderSnapshot(eq("K001B"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K001B", 10, 7, new BigDecimal("100.0"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(7, new BigDecimal("9300.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(7L);

        // BUY 체인 종료 — 이월이 아니라 완료. execution_status가 COMPLETED로 확정.
        Map<String, Object> decision = jdbc.queryForMap(
                "SELECT execution_status FROM decisions WHERE id = ?", d);
        assertThat(decision.get("execution_status")).isEqualTo("COMPLETED");
        assertThat(snapshots.findById(SYMBOL).orElseThrow().isLiquidationPending()).isFalse();

        // decisionTotalQty(7) > 0 → PARTIAL_UNIT, units +1(첫 진입).
        assertThat(portfolio.current().units()).isEqualTo(1);
        assertThat(alertRepo.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_UNIT"));
        assertThat(alertRepo.findAll()).noneSatisfy(a -> assertThat(a.getCode()).isEqualTo("EXECUTION_FAILED"));
    }

    /**
     * Critical 리뷰 수정 — BUY 전량 미체결(decisionTotalQty == 0)로 관찰 종결되면 EXECUTION_FAILED
     * 알림, units는 증가하지 않는다(체결이 전혀 없었으므로 진입 판정 자체가 성립하지 않음).
     */
    @Test
    void observed_cancel_with_zero_fill_buy_records_execution_failed_alert() {
        long d = insertDecision();
        jdbc.update("UPDATE decisions SET execution_status = 'IN_PROGRESS' WHERE id = ?", d);
        long orderId = insertOpenOrder(d, "K001Z", 10);
        seedSnapshot(0, 0.0, 10_000.0);

        when(broker.orderSnapshot(eq("K001Z"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K001Z", 10, 0, null, true, true)));
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("10000.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(0L);

        assertThat(portfolio.current().units()).isEqualTo(0);
        assertThat(alertRepo.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("EXECUTION_FAILED"));
        assertThat(alertRepo.findAll()).noneSatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_UNIT"));
    }

    /**
     * Important 리뷰 수정 — brokerTerminal && !cancelled인데 filled_qty < qty(예: IOC/FOK가 잔량을
     * 미체결로 종결)면 FILLED로 오판하면 안 된다. FILLED는 ck_orders_filled_terminal CHECK로
     * filled_qty == qty를 강제하므로, 이전 코드(brokerTerminal && !cancelled만으로 FILLED 판정)는
     * 이 케이스에서 finalizeChain의 tx가 CHECK violation으로 롤백됐다. 이제는 종결·잔량-있음
     * 경로(취소와 동일 시맨틱)로 처리해야 한다.
     */
    @Test
    void observed_terminal_not_cancelled_with_partial_fill_is_treated_as_cancelled_remainder() {
        long d = insertDecision();
        jdbc.update("UPDATE decisions SET execution_status = 'IN_PROGRESS' WHERE id = ?", d);
        long orderId = insertOpenOrder(d, "K001T", 10);
        seedSnapshot(0, 0.0, 10_000.0);

        // brokerTerminal=true, cancelled=false, filled=3/10 — IOC/FOK류 잔량 미체결 종결.
        when(broker.orderSnapshot(eq("K001T"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K001T", 10, 3, new BigDecimal("100.0"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(3, new BigDecimal("9700.00")));

        recon.reconcile(Trigger.PERIODIC);

        // ck_orders_filled_terminal CHECK(status<>'FILLED' OR filled_qty=qty)를 위반하지 않고
        // CANCELLED로 정상 커밋됐어야 한다 — 위반 시 이 쿼리 자체가 rollback된 tx 때문에 실패한다.
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) order.get("filled_qty")).longValue()).isEqualTo(3L);
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(3L);

        assertThat(alertRepo.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_UNIT"));
    }

    /**
     * Important 리뷰 수정(round 3) — OrderLifecycleService.applyFill의 chain-end 3분기 중
     * "SELL chain-end → units=0·entryBarTs=null 무조건 리셋"이 applyObservedFill에 빠져 있었다.
     * ghost-fill 경로(finalizeObservedFill, filled_qty==qty)로 관찰된 SELL 전량 체결도 정상 체결
     * 경로와 동일하게 리셋돼야 한다 — 리셋 전 상태를 units=1·entryBarTs 세팅으로 준비해 실제로
     * 리셋이 일어났음을 증명한다(0/null로 시작하면 안 바뀌어도 통과하므로 무의미).
     */
    @Test
    void observed_full_sell_fill_resets_units_and_entry_bar_ts() {
        long d = insertDecision();
        ZonedDateTime staleEntry = ZonedDateTime.parse("2026-08-01T02:00:00Z");
        long orderId = insertOpenSellOrder(d, "K001F", 10);
        seedSnapshot(1, 10.0, 9_000.0, staleEntry);   // before: units=1, entryBarTs 세팅됨(청산 전 보유 중)

        when(broker.orderSnapshot(eq("K001F"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K001F", 10, 10, new BigDecimal("100.0"), true, false)));
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("10000.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("FILLED");

        assertThat(portfolio.current().units()).isEqualTo(0);
        assertThat(portfolio.current().entryBarTs()).isNull();
        PortfolioSnapshotEntity snap = snapshots.findById(SYMBOL).orElseThrow();
        assertThat(snap.getUnits()).isEqualTo(0);
        assertThat(snap.getEntryBarTs()).isNull();
    }

    /**
     * Important 리뷰 수정(round 3), 코드 경로 도달성 확인 — finalizeObservedTerminalRemainder는
     * reconcileOrders의 fullyFilled==false 분기에서만 호출되므로, 이 메서드에 도달한 SELL은
     * remaining(=qty−filled)이 항상 > 0이다(filled==qty였다면 fullyFilled 분기가 먼저
     * finalizeObservedFill로 보냈을 것 — "전량 소진" SELL이 이 메서드의 markCompleted 분기에
     * 도달하는 경로는 코드상 존재하지 않는다, 위 finalizeObservedTerminalRemainder javadoc 참고).
     * 그래서 SELL이 이 메서드를 타면 항상 이월(ChainEnd.NOT_ENDING) — units/entryBarTs가
     * "보존"돼야 정상이다(SELL_CLOSE 리셋은 반대로 적용되면 안 됨). 이 테스트는 그 보존을
     * 검증해 SELL_CLOSE 리셋 로직이 이월 분기로 잘못 새지 않았음을 확인한다 — 브리프가 요청한
     * "fully-drained remainder variant"는 이 메서드 안에서는 SELL에 대해 존재하지 않는 상태이므로,
     * 대신 그 경계(remaining>0에서는 절대 리셋되지 않음)를 회귀 방어로 남긴다.
     */
    @Test
    void observed_sell_terminal_remainder_preserves_units_not_sell_close() {
        long d = insertDecision();
        ZonedDateTime staleEntry = ZonedDateTime.parse("2026-08-01T02:00:00Z");
        jdbc.update("UPDATE decisions SET execution_status = 'IN_PROGRESS' WHERE id = ?", d);
        long orderId = insertOpenSellOrder(d, "K001R", 10);
        seedSnapshot(1, 10.0, 9_000.0, staleEntry);

        // brokerTerminal=true, cancelled=true, filled=6/10(잔량 4) — 진짜 취소 확정, 잔량 있음.
        when(broker.orderSnapshot(eq("K001R"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K001R", 10, 6, new BigDecimal("100.0"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(4, new BigDecimal("9600.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("CANCELLED");   // 이월 — FILLED 아님

        // ChainEnd.NOT_ENDING — units/entryBarTs는 seedSnapshot이 세팅한 이전 값 그대로 보존.
        assertThat(portfolio.current().units()).isEqualTo(1);
        assertThat(portfolio.current().entryBarTs()).isEqualTo(staleEntry);
        PortfolioSnapshotEntity snap = snapshots.findById(SYMBOL).orElseThrow();
        assertThat(snap.getUnits()).isEqualTo(1);
        assertThat(snap.getEntryBarTs()).isEqualTo(staleEntry.toOffsetDateTime());
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

    /**
     * Fix 2 (P1) — SELL 이월 중(liquidationPending=true) 지연 체결이 잔량 전체를 채우면(canonical
     * == qty) correctCancelledToFilled뿐 아니라 같은 tx에서 체인 완결까지 커밋해야 한다: snapshot
     * units=0/entryBarTs=null/liquidationPending=false + decision COMPLETED. 커밋 후 메모리도
     * snapshot과 일치해야 한다(spec §2).
     */
    @Test
    void late_fill_completes_carried_liquidation_chain_in_same_tx() {
        long d = insertDecision();
        jdbc.update("UPDATE decisions SET execution_status = 'IN_PROGRESS' WHERE id = ?", d);
        ZonedDateTime staleEntry = ZonedDateTime.parse("2026-08-01T02:00:00Z");
        long orderId = insertCancelledOrder(d, "K010", 10, 6, new BigDecimal("600.0000"), "SELL");
        insertExecution(d, orderId, 100.0, -6.0, 1, 6.0, 9600.0);
        seedSnapshot(1, 6.0, 9600.0, staleEntry, true);   // liquidation_pending=true — carried

        // KIS 누적 체결이 10(=qty) 도달 — 정정 후 FILLED이면서 잔량 0 도달(체인 완결 트리거)
        when(broker.orderSnapshot(eq("K010"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K010", 10, 10, new BigDecimal("100.0"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(0, new BigDecimal("10000.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("FILLED");
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(10L);

        PortfolioSnapshotEntity snap = snapshots.findById(SYMBOL).orElseThrow();
        assertThat(snap.isLiquidationPending()).isFalse();
        assertThat(snap.getUnits()).isEqualTo(0);
        assertThat(snap.getEntryBarTs()).isNull();

        Map<String, Object> decision = jdbc.queryForMap(
                "SELECT execution_status FROM decisions WHERE id = ?", d);
        assertThat(decision.get("execution_status")).isEqualTo("COMPLETED");

        // 커밋 후 메모리 == snapshot (spec §2)
        assertThat(portfolio.current().units()).isEqualTo(0);
        assertThat(portfolio.current().entryBarTs()).isNull();
        assertThat(portfolio.current().shares()).isEqualTo(snap.getShares().doubleValue());
    }

    /**
     * Fix 2 BUY 대응 사례 — zero-fill로 give-up(units=0, decisionTotal=0)한 BUY가 뒤늦게 전량
     * 체결로 정정되면(decisionTotal 0→10) "units=0인데 shares>0" 조합을 남기지 않고 같은 tx에서
     * units를 1로 보정 + PARTIAL_UNIT 알림을 남긴다.
     */
    @Test
    void late_fill_on_zero_fill_buy_corrects_units_with_partial_unit_alert() {
        long d = insertDecision();
        jdbc.update("UPDATE decisions SET execution_status = 'IN_PROGRESS' WHERE id = ?", d);
        long orderId = insertCancelledOrder(d, "K011", 10, 0, BigDecimal.ZERO.setScale(4), "BUY");
        seedSnapshot(0, 0.0, 10_000.0);

        when(broker.orderSnapshot(eq("K011"), any(LocalDate.class)))
                .thenReturn(Optional.of(new KisOrderSnapshot("K011", 10, 10, new BigDecimal("100.0"), true, true)));
        when(broker.balance()).thenReturn(new KisBalance(10, new BigDecimal("9000.00")));

        recon.reconcile(Trigger.PERIODIC);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(order.get("status")).isEqualTo("FILLED");
        assertThat(((Number) order.get("accounted_filled_qty")).longValue()).isEqualTo(10L);

        PortfolioSnapshotEntity snap = snapshots.findById(SYMBOL).orElseThrow();
        assertThat(snap.getUnits()).isEqualTo(1);
        assertThat(snap.getEntryBarTs()).isNotNull();
        assertThat(portfolio.current().units()).isEqualTo(1);
        assertThat(portfolio.current().shares()).isEqualTo(10.0);

        assertThat(alertRepo.findAll()).anySatisfy(a -> assertThat(a.getCode()).isEqualTo("PARTIAL_UNIT"));
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
