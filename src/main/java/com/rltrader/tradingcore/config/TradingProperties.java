package com.rltrader.tradingcore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        String symbol,
        String predictBaseUrl,
        int delaySeconds,
        int maxUnitsCap,
        double maxCapitalCap,
        double dryRunInitialCash,
        int connectTimeoutMs,
        int readTimeoutMs,
        int controlTimeoutMs,
        int readyWaitMaxSeconds) {
}
