package com.rltrader.tradingcore;

import static org.assertj.core.api.Assertions.assertThat;

import com.rltrader.tradingcore.execution.DryRunExecutor;
import com.rltrader.tradingcore.execution.ExecutionPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContextLoadsTest extends AbstractIntegrationTest {
    @Test
    void contextLoadsAndFlywayMigrates() {
        // Flyway V1이 적용되고 컨텍스트가 뜨면 성공 — 스키마 오타를 여기서 잡는다
    }

    @Autowired ExecutionPort executor;

    @Test
    void defaultProfileWiresDryRunExecutorOnly() {
        // 기본(비-live) 프로파일에서는 ExecutionPort 빈이 DryRunExecutor 단독이어야 한다
        // (KisOrderExecutor는 @Profile("live") 전용, Task 12 빈 그래프 계약)
        assertThat(executor).isInstanceOf(DryRunExecutor.class);
    }
}
