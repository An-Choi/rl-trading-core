package com.rltrader.tradingcore.scheduler;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.cycle.DecisionCycleService;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.predict.HandshakeValidator;
import com.rltrader.tradingcore.predict.PredictClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * spec §4/§7: /ready 대기 → 핸드셰이크 검증 → 복원 → cycle 초기화. 스케줄러 enable은 호출자 몫.
 *
 * <p>live 확장(Task 14, spec §7) — live 전용 협력자(halt 게이트·토큰·복구·대사를 한데 모은
 * {@link BootRecoveryService})는 {@link ObjectProvider}로 주입한다(LiveCycleOrchestrator/
 * DecisionScheduler와 동일한 관례, Task 12/13). BootRecoveryService는 {@code @Profile("live")}
 * 전용 컴포넌트이므로 dry-run 프로파일에는 이 빈이 없어 {@code getIfAvailable()}이 항상 null을
 * 반환한다 — 1차 4단계 경로(핸드셰이크 → restore → initialize)가 완전히 무변경으로 유지된다.
 * live 프로파일에서는 이 빈이 존재하므로 기동 순서 ①~⑩ 전체(recovery가 ①~⑨ 소유, cycle.initialize가
 * 사실상 ⑩ 직전 마지막 단계 — scheduler.enable()은 여전히 StartupRunner 호출자 몫)를 수행한다.
 */
@Component
public class BootSequence {

    private static final Logger log = LoggerFactory.getLogger(BootSequence.class);

    private final PredictClient predict;
    private final DecisionCycleService cycle;
    private final PortfolioManager portfolio;
    private final TradingProperties props;
    private final ObjectProvider<BootRecoveryService> liveRecovery;

    public BootSequence(PredictClient predict, DecisionCycleService cycle,
            PortfolioManager portfolio, TradingProperties props,
            ObjectProvider<BootRecoveryService> liveRecovery) {
        this.predict = predict;
        this.cycle = cycle;
        this.portfolio = portfolio;
        this.props = props;
        this.liveRecovery = liveRecovery;
    }

    public ArtifactContext prepare() throws InterruptedException {
        BootRecoveryService recovery = liveRecovery.getIfAvailable();
        if (recovery != null) {
            recovery.assertNoActiveHalt();   // ① — /ready 폴링(predict 호출)보다도 먼저: HALT가
            // 걸려 있으면 predict 서버 상태와 무관하게 기동 자체를 시작하지 않는다.
        }

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

        if (recovery != null) {
            // live 기동 순서(spec §7 ②~⑨) — recovery가 ②(토큰)부터 ⑨(reconcile STARTUP)까지
            // 전부 소유한다. ③ 핸드셰이크는 metadata 검증만(restore 호출 없음) — 원본 복원은
            // recovery 내부 ④가 restoreRaw로 수행한다.
            recovery.ensureToken();          // ②
            ArtifactContext ctx = HandshakeValidator.validate(predict.metadata(), props);   // ③
            log.info("handshake ok (live): artifact={} fs={}", ctx.artifactId(), ctx.featureSchemaVersion());
            recovery.recoverAndVerify(ctx);   // ④~⑨
            cycle.initialize(ctx);
            return ctx;
        }

        // dry-run 경로 — 1차 그대로 무변경.
        ArtifactContext ctx = HandshakeValidator.validate(predict.metadata(), props);
        log.info("handshake ok: artifact={} fs={}", ctx.artifactId(), ctx.featureSchemaVersion());
        portfolio.restore(ctx);
        cycle.initialize(ctx);
        return ctx;
    }
}
