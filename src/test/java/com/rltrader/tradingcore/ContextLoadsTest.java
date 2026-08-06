package com.rltrader.tradingcore;

import org.junit.jupiter.api.Test;

class ContextLoadsTest extends AbstractIntegrationTest {
    @Test
    void contextLoadsAndFlywayMigrates() {
        // Flyway V1이 적용되고 컨텍스트가 뜨면 성공 — 스키마 오타를 여기서 잡는다
    }
}
