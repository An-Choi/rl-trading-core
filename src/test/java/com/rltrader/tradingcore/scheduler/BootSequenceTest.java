package com.rltrader.tradingcore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.cycle.DecisionCycleService;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.predict.MetadataResponse;
import com.rltrader.tradingcore.predict.PredictClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

class BootSequenceTest {

    private static TradingProperties props(int readyWaitSeconds) {
        return new TradingProperties("005930", "http://x", 20, 5, 10_000.0, 10_000.0,
                5000, 240_000, 5000, readyWaitSeconds);
    }

    /** dry-run 프로파일 동형 — live 전용 빈(BootRecoveryService)이 없는 ObjectProvider. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<BootRecoveryService> emptyRecoveryProvider() {
        ObjectProvider<BootRecoveryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static MetadataResponse validMeta() {
        return new MetadataResponse("ppo-fs3-x", 3,
                Map.of("type", "discrete", "n", 3, "labels", List.of("hold", "add_unit", "clear")),
                Map.of("unit_fraction", 0.199, "initial_cash", 10_000.0, "max_units", 5),
                "sha");
    }

    @Test
    void readyTimeoutThrows() {
        // monotonic deadline: ready-wait 0초 → 첫 실패 직후 timeout (고정 Clock 무관)
        PredictClient predict = mock(PredictClient.class);
        when(predict.ready()).thenReturn(false);
        BootSequence boot = new BootSequence(predict, mock(DecisionCycleService.class),
                mock(PortfolioManager.class), props(0), emptyRecoveryProvider());
        assertThatThrownBy(boot::prepare)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not ready");
    }

    @Test
    void successPathRunsHandshakeRestoreInitializeInOrder() throws Exception {
        PredictClient predict = mock(PredictClient.class);
        when(predict.ready()).thenReturn(true);
        when(predict.metadata()).thenReturn(validMeta());
        DecisionCycleService cycle = mock(DecisionCycleService.class);
        PortfolioManager portfolio = mock(PortfolioManager.class);
        BootSequence boot = new BootSequence(predict, cycle, portfolio, props(5), emptyRecoveryProvider());
        ArtifactContext ctx = boot.prepare();
        assertThat(ctx.artifactId()).isEqualTo("ppo-fs3-x");
        InOrder order = inOrder(portfolio, cycle);       // 복원 → 초기화 순서 보장
        order.verify(portfolio).restore(ctx);
        order.verify(cycle).initialize(ctx);
    }

    @Test
    void invalidMetadataPropagatesAndNothingInitialized() {
        PredictClient predict = mock(PredictClient.class);
        when(predict.ready()).thenReturn(true);
        MetadataResponse bad = new MetadataResponse("a", 3,
                Map.of("type", "discrete", "n", 3, "labels", List.of("hold", "clear", "add_unit")),
                Map.of("unit_fraction", 0.199, "initial_cash", 10_000.0, "max_units", 5), "sha");
        when(predict.metadata()).thenReturn(bad);
        DecisionCycleService cycle = mock(DecisionCycleService.class);
        PortfolioManager portfolio = mock(PortfolioManager.class);
        BootSequence boot = new BootSequence(predict, cycle, portfolio, props(5), emptyRecoveryProvider());
        assertThatThrownBy(boot::prepare).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(portfolio, cycle);          // 검증 실패 시 복원·초기화 미실행
        // (StartupRunner는 prepare() 예외로 scheduler.enable()에 도달하지 못한다)
    }
}
