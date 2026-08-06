package com.rltrader.tradingcore.scheduler;

import com.rltrader.tradingcore.config.SmokeProperties;
import com.rltrader.tradingcore.cycle.CycleOutcome;
import com.rltrader.tradingcore.cycle.DecisionCycleService;
import com.rltrader.tradingcore.domain.TradingGrid;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * spec §7.7 수동 스모크 — 단일 lifecycle: prepare()(핸드셰이크→복원→초기화) 완료 후에만
 * bar 실행. 운영 스케줄러는 smoke 컨텍스트에 없다(StartupRunner @Profile("!test & !smoke")).
 * as_of는 각 bar 라벨로 명시 — 운영 경로만 as_of를 생략한다.
 */
@Component
@Profile("smoke")
public class SmokeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SmokeRunner.class);

    private final BootSequence boot;
    private final DecisionCycleService cycle;
    private final SmokeProperties smoke;
    private final com.rltrader.tradingcore.config.TradingProperties props;
    private final com.rltrader.tradingcore.portfolio.PortfolioManager portfolio;
    private final com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository snapshots;
    private final Clock clock;
    private final ConfigurableApplicationContext context;

    public SmokeRunner(BootSequence boot, DecisionCycleService cycle, SmokeProperties smoke,
            com.rltrader.tradingcore.config.TradingProperties props,
            com.rltrader.tradingcore.portfolio.PortfolioManager portfolio,
            com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository snapshots,
            Clock clock, ConfigurableApplicationContext context) {
        this.boot = boot;
        this.cycle = cycle;
        this.smoke = smoke;
        this.props = props;
        this.portfolio = portfolio;
        this.snapshots = snapshots;
        this.clock = clock;
        this.context = context;
    }

    /** startBar부터 count개 bar 라벨 (15:20에서 절단). */
    static List<ZonedDateTime> smokeBars(LocalDate day, LocalTime startBar, int count) {
        int startOffset = TradingGrid.offsetOf(startBar)
                .orElseThrow(() -> new IllegalArgumentException("start-bar not on grid: " + startBar));
        List<ZonedDateTime> bars = new ArrayList<>();
        for (int i = startOffset; i < Math.min(startOffset + count, TradingGrid.BARS_PER_DAY); i++) {
            bars.add(ZonedDateTime.of(day, TradingGrid.FIRST_BAR.plusMinutes(5L * i), TradingGrid.KST));
        }
        return bars;
    }

    @Override
    public void run(String... args) throws Exception {
        var ctx = boot.prepare();
        if (smoke.mode() == SmokeProperties.SmokeMode.RESTORE_ONLY) {
            // 재기동 검증 전용: 같은 bar 재실행 금지 (currentBar < entryBarTs 음수
            // bars_since_entry / 최종 상태로 과거 bar 추론 방지). 검증 실패 시 예외 = 실패 종료.
            if (snapshots.findById(props.symbol()).isEmpty()) {
                throw new IllegalStateException(
                        "restore-only인데 snapshot이 없음 — 1차 replay run이 선행되어야 한다");
            }
            var state = portfolio.current();
            log.info("restore-only: artifact={} units={} shares={} cash={} entryBarTs={}",
                    ctx.artifactId(), state.units(), state.shares(), state.cash(),
                    state.entryBarTs());
            context.close();   // 검증 통과 후에만 종료
            return;
        }
        LocalDate day = LocalDate.now(clock);
        List<ZonedDateTime> bars = smokeBars(day, LocalTime.parse(smoke.startBar()), smoke.bars());
        Map<CycleOutcome, Integer> counts = new EnumMap<>(CycleOutcome.class);
        for (ZonedDateTime bar : bars) {
            CycleOutcome outcome = cycle.runCycle(bar, bar.toOffsetDateTime().toString());
            counts.merge(outcome, 1, Integer::sum);
            log.info("smoke bar {} -> {}", bar, outcome);
            if (outcome == CycleOutcome.HALTED) {
                // 예외 전파로 실패 종료 — CommandLineRunner 예외는 non-zero exit (context는 Boot가 정리)
                throw new IllegalStateException("smoke HALTED at " + bar + " — 실패 종료");
            }
        }
        if (counts.getOrDefault(CycleOutcome.EXECUTED, 0) == 0) {
            // 전부 NOOP이면 아무것도 검증되지 않은 것 — false positive 방지 (Task 9 완료 기준)
            throw new IllegalStateException("smoke failed: EXECUTED 0건 — " + counts);
        }
        log.info("smoke done: {}", counts);
        context.close();   // 모든 검증 통과 후에만 정상 종료
    }
}
