-- Task 13 spec §6/§8. 대사(reconciliation) 결과 기록.
-- 브리프 SQL 대비 편차: 컬럼명 trigger는 PostgreSQL 예약어라 그대로 쓰면 파싱 실패한다 —
-- trigger_source로 명명(엔티티 필드도 triggerSource). CHECK 값 목록은 브리프와 동일.

CREATE TABLE reconciliations (
    id             BIGSERIAL PRIMARY KEY,
    trigger_source VARCHAR(16) NOT NULL CHECK (trigger_source IN ('PERIODIC','POST_ORDER','STARTUP','RECONNECT')),
    level          VARCHAR(8)  NOT NULL CHECK (level IN ('ORDER','BALANCE')),
    result         VARCHAR(12) NOT NULL CHECK (result IN ('CLEAN','SYNCED','UNEXPLAINED')),
    detail         JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
