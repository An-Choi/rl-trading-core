-- Task 5-2차 spec §8. 수량은 BIGINT, 금액은 NUMERIC — live 회계는 부동소수점 금지 (§1 타입 계약).

ALTER TABLE decisions
    ADD COLUMN execution_status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED'
        CHECK (execution_status IN ('IN_PROGRESS', 'COMPLETED'));
-- 기존 행(1차 dry-run)은 전부 완료된 결정이므로 COMPLETED 기본값이 옳다.

CREATE TABLE orders (
    id                      BIGSERIAL PRIMARY KEY,
    decision_id             BIGINT NOT NULL REFERENCES decisions (id),
    attempt                 INT    NOT NULL,
    trading_date            DATE   NOT NULL,
    symbol                  VARCHAR(6)  NOT NULL,
    side                    VARCHAR(4)  NOT NULL CHECK (side IN ('BUY', 'SELL')),
    qty                     BIGINT NOT NULL,
    order_type              VARCHAR(16) NOT NULL,
    status                  VARCHAR(16) NOT NULL CHECK (status IN
        ('NEW','SUBMITTED','SUBMIT_UNKNOWN','ACKED','PARTIALLY_FILLED',
         'CANCEL_PENDING','FILLED','CANCELLED','REJECTED','ABORTED')),
    kis_order_no            VARCHAR(16),
    filled_qty              BIGINT NOT NULL DEFAULT 0,
    accounted_filled_qty    BIGINT NOT NULL DEFAULT 0,
    accounted_fill_notional NUMERIC(18, 4) NOT NULL DEFAULT 0,
    avg_fill_price          NUMERIC(18, 4),
    submitted_at            TIMESTAMPTZ,
    terminal_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_decision_attempt UNIQUE (decision_id, attempt),
    CONSTRAINT ck_orders_qty        CHECK (qty > 0),
    CONSTRAINT ck_orders_attempt    CHECK (attempt >= 1),
    CONSTRAINT ck_orders_filled     CHECK (filled_qty >= 0 AND filled_qty <= qty),
    CONSTRAINT ck_orders_accounted  CHECK (accounted_filled_qty >= 0
                                       AND accounted_filled_qty <= filled_qty),
    CONSTRAINT ck_orders_filled_terminal CHECK (status <> 'FILLED' OR filled_qty = qty)
);
-- 같은 KIS 주문을 두 로컬 행이 주장하는 버그를 DB가 차단 (spec §8)
CREATE UNIQUE INDEX uq_orders_trading_date_kis_no
    ON orders (trading_date, kis_order_no) WHERE kis_order_no IS NOT NULL;
CREATE INDEX ix_orders_nonterminal ON orders (status)
    WHERE status NOT IN ('FILLED','CANCELLED','REJECTED','ABORTED');

CREATE TABLE order_events (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders (id),
    from_status VARCHAR(16) NOT NULL,
    to_status   VARCHAR(16) NOT NULL,
    detail      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE execution_adjustments (
    id                 BIGSERIAL PRIMARY KEY,
    order_id           BIGINT NOT NULL REFERENCES orders (id),
    from_filled_qty    BIGINT NOT NULL,
    to_filled_qty      BIGINT NOT NULL,
    from_fill_notional NUMERIC(18, 4) NOT NULL,
    to_fill_notional   NUMERIC(18, 4) NOT NULL,
    qty_delta          BIGINT NOT NULL,
    notional_delta     NUMERIC(18, 4) NOT NULL,
    reason             VARCHAR(64) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_adjustments_order_to UNIQUE (order_id, to_filled_qty),
    CONSTRAINT ck_adj_forward  CHECK (to_filled_qty > from_filled_qty),
    CONSTRAINT ck_adj_delta    CHECK (qty_delta = to_filled_qty - from_filled_qty AND qty_delta > 0),
    CONSTRAINT ck_adj_notional CHECK (notional_delta >= 0)
);

CREATE TABLE halts (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(48) NOT NULL,
    detail     JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cleared_at TIMESTAMPTZ,
    cleared_by VARCHAR(64)
);

ALTER TABLE executions ADD COLUMN order_id BIGINT REFERENCES orders (id);
CREATE UNIQUE INDEX uq_executions_order ON executions (order_id) WHERE order_id IS NOT NULL;
-- dry-run 행은 order_id NULL 유지 — 기존 경로 무변경 (spec §8)

-- live 회계의 진실은 DB NUMERIC (spec §1) — 기존 DOUBLE 컬럼을 NUMERIC으로 변환.
-- 정밀도 무지정 NUMERIC: BigDecimal.valueOf(double)의 최단 십진 표현이 그대로 보존되어
-- dry-run double 값도 손실 없이 왕복한다 (PortfolioManagerRestoreTest 회귀 조건).
ALTER TABLE executions
    ALTER COLUMN fill_price   TYPE NUMERIC,
    ALTER COLUMN shares_delta TYPE NUMERIC,
    ALTER COLUMN shares_after TYPE NUMERIC,
    ALTER COLUMN cash_after   TYPE NUMERIC;
ALTER TABLE portfolio_snapshots
    ALTER COLUMN shares TYPE NUMERIC,
    ALTER COLUMN cash   TYPE NUMERIC;

ALTER TABLE portfolio_snapshots
    ADD COLUMN liquidation_pending BOOLEAN NOT NULL DEFAULT FALSE;

-- 회계 원장 view (spec §8). executions.shares_delta는 이미 부호를 가진다(매도 음수) —
-- adjustments는 항상 양수 delta이므로 주문 side로 부호를 붙여야 SELL 지연 체결이
-- 보유수량을 증가시키는 버그가 생기지 않는다.
CREATE VIEW accounting_ledger AS
SELECT e.order_id,
       e.shares_delta::numeric              AS qty_delta,
       (e.fill_price * e.shares_delta)::numeric AS notional_delta
FROM executions e WHERE e.order_id IS NOT NULL
UNION ALL
SELECT a.order_id,
       CASE o.side WHEN 'BUY' THEN a.qty_delta ELSE -a.qty_delta END::numeric,
       CASE o.side WHEN 'BUY' THEN a.notional_delta ELSE -a.notional_delta END
FROM execution_adjustments a JOIN orders o ON o.id = a.order_id;
