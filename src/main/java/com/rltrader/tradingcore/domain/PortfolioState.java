package com.rltrader.tradingcore.domain;

import java.time.ZonedDateTime;

/** 불변 포트폴리오 상태. bars_since_entry는 파생값 — entryBarTs에서 재계산 (spec §2). */
public record PortfolioState(int units, double shares, double cash, ZonedDateTime entryBarTs) {

    public static PortfolioState flat(double initialCash) {
        return new PortfolioState(0, 0.0, initialCash, null);
    }

    public int barsSinceEntry(ZonedDateTime currentBar) {
        if (units == 0 || entryBarTs == null) {
            return 0;
        }
        return (int) TradingGrid.barsBetween(entryBarTs, currentBar);
    }
}
