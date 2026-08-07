package com.rltrader.tradingcore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kis")
public record KisProperties(
        String baseUrl, String appKey, String appSecret,
        String accountNo, String accountProductCode,
        int connectTimeoutMs, int readTimeoutMs) {}
