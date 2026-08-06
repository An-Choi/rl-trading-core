package com.rltrader.tradingcore.scheduler;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.TradingGrid;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/** delay-seconds가 실제 트리거에 반영되도록 cron을 코드로 구성 (하드코딩 금지). */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    private final TradingProperties props;
    private final DecisionScheduler scheduler;

    public SchedulingConfig(TradingProperties props, DecisionScheduler scheduler) {
        this.props = props;
        this.scheduler = scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        int delay = props.delaySeconds();
        if (delay < 0 || delay > 59) {   // cron 초 필드 범위 — bar 라벨(분 절단)과의 정합 조건
            throw new IllegalStateException("delay-seconds must be in 0..59: " + delay);
        }
        String cron = "%d 0/5 9-15 * * MON-FRI".formatted(delay);
        registrar.addCronTask(new CronTask(scheduler::tick, new CronTrigger(cron, TradingGrid.KST)));
    }
}
