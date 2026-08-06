package com.rltrader.tradingcore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "smoke")
public record SmokeProperties(String clockAt, String startBar, int bars,
                              @DefaultValue("replay") SmokeMode mode) {

    /** enum 바인딩 — 오타("restore-onIy" 등)는 기동 실패, 조용한 replay 전락 방지. */
    public enum SmokeMode { REPLAY, RESTORE_ONLY }   // relaxed binding: "restore-only" → RESTORE_ONLY
}
