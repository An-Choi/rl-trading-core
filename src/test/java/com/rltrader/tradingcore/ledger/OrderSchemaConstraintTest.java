package com.rltrader.tradingcore.ledger;

import static org.assertj.core.api.Assertions.*;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class OrderSchemaConstraintTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private long insertDecision() {
        return jdbc.queryForObject("""
            INSERT INTO decisions (symbol, bar_ts, action, label, action_mask, observation,
                bar_close, artifact_id, execution_status)
            VALUES ('005930', now(), 1, 'add_unit', '[]', '[]', 100.0, 'a1', 'IN_PROGRESS')
            RETURNING id""", Long.class);
    }

    private long insertOrder(long decisionId, String status, long qty, long filled, long accounted) {
        return jdbc.queryForObject("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, filled_qty, accounted_filled_qty, accounted_fill_notional)
            VALUES (?, 1, '2026-08-07', '005930', 'BUY', ?, 'MARKET', ?, ?, ?, 0)
            RETURNING id""", Long.class, decisionId, qty, status, filled, accounted);
    }

    @Test
    void filled_requires_full_qty() {
        long d = insertDecision();
        assertThatThrownBy(() -> insertOrder(d, "FILLED", 10, 5, 5))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accounted_cannot_exceed_filled() {
        long d = insertDecision();
        assertThatThrownBy(() -> insertOrder(d, "ACKED", 10, 3, 5))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicate_attempt_rejected() {
        long d = insertDecision();
        insertOrder(d, "ACKED", 10, 0, 0);
        assertThatThrownBy(() -> insertOrder(d, "ACKED", 10, 0, 0))   // 같은 (decision, attempt=1)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void same_kis_order_no_same_day_rejected() {
        long d = insertDecision();
        long o1 = insertOrder(d, "ACKED", 10, 0, 0);
        jdbc.update("UPDATE orders SET kis_order_no='0001' WHERE id=?", o1);
        long d2 = jdbc.queryForObject("""
            INSERT INTO decisions (symbol, bar_ts, action, label, action_mask, observation,
                bar_close, artifact_id, execution_status)
            VALUES ('005930', now() + interval '5 minutes', 1, 'add_unit', '[]', '[]', 100.0,
                'a1', 'IN_PROGRESS') RETURNING id""", Long.class);
        long o2 = insertOrder(d2, "ACKED", 10, 0, 0);
        assertThatThrownBy(() ->
                jdbc.update("UPDATE orders SET kis_order_no='0001' WHERE id=?", o2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adjustment_delta_checks() {
        long d = insertDecision();
        long o = insertOrder(d, "CANCELLED", 10, 7, 7);
        // to <= from 위반
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO execution_adjustments (order_id, from_filled_qty, to_filled_qty,
                from_fill_notional, to_fill_notional, qty_delta, notional_delta, reason)
            VALUES (?, 7, 7, 700, 700, 0, 0, 'late-fill')""", o))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accounting_ledger_view_sums_executions_and_adjustments() {
        long d = insertDecision();
        long o = insertOrder(d, "CANCELLED", 10, 7, 7);
        jdbc.update("""
            INSERT INTO executions (decision_id, order_id, fill_price, shares_delta,
                units_after, shares_after, cash_after) VALUES (?, ?, 100, 5, 1, 5, 500)""", d, o);
        jdbc.update("""
            INSERT INTO execution_adjustments (order_id, from_filled_qty, to_filled_qty,
                from_fill_notional, to_fill_notional, qty_delta, notional_delta, reason)
            VALUES (?, 5, 7, 500, 721, 2, 221, 'late-fill')""", o);
        Double total = jdbc.queryForObject(
            "SELECT SUM(qty_delta) FROM accounting_ledger WHERE order_id = ?", Double.class, o);
        assertThat(total).isEqualTo(7.0);   // 5(execution) + 2(adjustment, BUY라 +)
    }

    @Test
    void sell_adjustment_is_signed_negative_in_ledger() {
        // clear(SELL) 주문: execution -8주 + 지연 체결 adjustment 2주 → 원장 합 -10 (보유 감소)
        long d = insertDecision();
        long o = jdbc.queryForObject("""
            INSERT INTO orders (decision_id, attempt, trading_date, symbol, side, qty, order_type,
                status, filled_qty, accounted_filled_qty, accounted_fill_notional)
            VALUES (?, 1, '2026-08-07', '005930', 'SELL', 10, 'MARKET', 'CANCELLED', 10, 10, 1000)
            RETURNING id""", Long.class, d);
        jdbc.update("""
            INSERT INTO executions (decision_id, order_id, fill_price, shares_delta,
                units_after, shares_after, cash_after) VALUES (?, ?, 100, -8, 0, 2, 800)""", d, o);
        jdbc.update("""
            INSERT INTO execution_adjustments (order_id, from_filled_qty, to_filled_qty,
                from_fill_notional, to_fill_notional, qty_delta, notional_delta, reason)
            VALUES (?, 8, 10, 800, 1000, 2, 200, 'late-fill')""", o);
        Double total = jdbc.queryForObject(
            "SELECT SUM(qty_delta) FROM accounting_ledger WHERE order_id = ?", Double.class, o);
        assertThat(total).isEqualTo(-10.0);   // SELL adjustment는 음수로 합산
    }
}
