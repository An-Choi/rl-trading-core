package com.rltrader.tradingcore.scheduler;

import com.rltrader.tradingcore.config.SmokeProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("smoke")
public class SmokeClockConfig {
    @Bean
    public Clock clock(SmokeProperties smoke) {   // ClockConfig(!smoke)와 상호 배타 — 활성 Clock은 1개
        ZonedDateTime at = LocalDateTime.parse(smoke.clockAt()).atZone(ZoneId.of("Asia/Seoul"));
        return Clock.fixed(at.toInstant(), at.getZone());
    }
}
