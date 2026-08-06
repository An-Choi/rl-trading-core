package com.rltrader.tradingcore.scheduler;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.cycle.DecisionCycleService;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.predict.HandshakeValidator;
import com.rltrader.tradingcore.predict.PredictClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** spec §4: /ready 대기 → 핸드셰이크 검증 → 복원 → cycle 초기화. 스케줄러 enable은 호출자 몫. */
@Component
public class BootSequence {

    private static final Logger log = LoggerFactory.getLogger(BootSequence.class);

    private final PredictClient predict;
    private final DecisionCycleService cycle;
    private final PortfolioManager portfolio;
    private final TradingProperties props;

    public BootSequence(PredictClient predict, DecisionCycleService cycle,
            PortfolioManager portfolio, TradingProperties props) {
        this.predict = predict;
        this.cycle = cycle;
        this.portfolio = portfolio;
        this.props = props;
    }

    public ArtifactContext prepare() throws InterruptedException {
        // 경과시간은 monotonic — 시장 Clock과 분리. smoke의 Clock.fixed에서는 millis()가
        // 흐르지 않아 Clock 기반 deadline은 영원히 도달하지 않는다.
        long deadlineNanos = System.nanoTime() + props.readyWaitMaxSeconds() * 1_000_000_000L;
        while (!predict.ready()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException(
                        "predict server not ready within " + props.readyWaitMaxSeconds() + "s");
            }
            Thread.sleep(2000);
        }
        ArtifactContext ctx = HandshakeValidator.validate(predict.metadata(), props);
        log.info("handshake ok: artifact={} fs={}", ctx.artifactId(), ctx.featureSchemaVersion());
        portfolio.restore(ctx);
        cycle.initialize(ctx);
        return ctx;
    }
}
