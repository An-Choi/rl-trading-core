package com.rltrader.tradingcore.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class ClockConfig {
    @Bean
    @Profile("!smoke")   // smoke의 고정 Clock(Task 7 SmokeClockConfig)과 상호 배타 — 활성 빈은 항상 1개
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
