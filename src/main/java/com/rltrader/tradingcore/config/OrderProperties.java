package com.rltrader.tradingcore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 값은 전부 초기값 — 모의투자 실측 후 확정 (spec §3 매직 넘버 금지). */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(
        long fillPollIntervalMs, long fillTimeoutMs,
        String closingAuctionDeadline,
        int submitRetryMax, int rejectRetryMax,
        int submitUnknownMaxLookups, long reconIntervalMs) {}
