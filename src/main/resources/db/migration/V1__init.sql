CREATE TABLE decisions (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(6)  NOT NULL,
    bar_ts      TIMESTAMPTZ NOT NULL,
    action      INT         NOT NULL,
    label       VARCHAR(16) NOT NULL,
    action_mask JSONB       NOT NULL,
    observation JSONB       NOT NULL,
    bar_close   DOUBLE PRECISION NOT NULL,
    artifact_id VARCHAR(64) NOT NULL,
    latency_ms  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_decisions_symbol_bar UNIQUE (symbol, bar_ts)
);

CREATE TABLE executions (
    id           BIGSERIAL PRIMARY KEY,
    decision_id  BIGINT NOT NULL REFERENCES decisions (id),
    fill_price   DOUBLE PRECISION NOT NULL,
    shares_delta DOUBLE PRECISION NOT NULL,
    units_after  INT    NOT NULL,
    shares_after DOUBLE PRECISION NOT NULL,
    cash_after   DOUBLE PRECISION NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE alerts (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(32) NOT NULL,
    detail     JSONB,
    bar_ts     TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- bars_since_entry는 파생값이라 저장하지 않는다 — entry_bar_ts에서 재계산 (spec §2·§5)
CREATE TABLE portfolio_snapshots (
    symbol       VARCHAR(6) PRIMARY KEY,
    units        INT    NOT NULL,
    shares       DOUBLE PRECISION NOT NULL,
    cash         DOUBLE PRECISION NOT NULL,
    entry_bar_ts TIMESTAMPTZ,
    artifact_id  VARCHAR(64) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);
