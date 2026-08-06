package com.rltrader.tradingcore.scheduler;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !smoke")
public class StartupRunner implements ApplicationRunner {

    private final BootSequence boot;
    private final DecisionScheduler scheduler;

    public StartupRunner(BootSequence boot, DecisionScheduler scheduler) {
        this.boot = boot;
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boot.prepare();
        scheduler.enable();
    }
}
