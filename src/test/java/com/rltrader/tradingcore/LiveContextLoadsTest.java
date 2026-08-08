package com.rltrader.tradingcore;

import static org.assertj.core.api.Assertions.assertThat;

import com.rltrader.tradingcore.cycle.LiveCycleOrchestrator;
import com.rltrader.tradingcore.execution.ExecutionPort;
import com.rltrader.tradingcore.execution.KisOrderExecutor;
import com.rltrader.tradingcore.kis.BrokerPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Task 12 — live 프로파일 전체 컨텍스트가 순환 의존 없이 뜨는지 (빈 그래프 회귀 방어).
 * BrokerPort는 실제 KIS 호출을 피하기 위해 mock으로 대체 — 컨텍스트 로딩 자체가 목적이라
 * 동작 검증은 최소화한다. recon-interval-ms를 크게 잡아 PeriodicReconciliationTrigger(Task 13)의
 * 실 벽시계 @Scheduled 발화가 이 테스트 도중 BrokerPort 미스텁 호출(NPE)로 새지 않게 한다.
 */
@ActiveProfiles({"test", "live"})
@TestPropertySource(properties = "order.recon-interval-ms=3600000")
class LiveContextLoadsTest extends AbstractIntegrationTest {

    @MockitoBean BrokerPort broker;

    @Autowired ExecutionPort executor;
    @Autowired LiveCycleOrchestrator orchestrator;

    @Test
    void liveContextLoadsWithoutCircularDependency() {
        // 컨텍스트가 뜨고 두 빈이 주입되면 성공 — 순환 의존이 있었다면 기동 자체가 실패했을 것
        assertThat(executor).isInstanceOf(KisOrderExecutor.class);
        assertThat(orchestrator).isNotNull();
    }
}
