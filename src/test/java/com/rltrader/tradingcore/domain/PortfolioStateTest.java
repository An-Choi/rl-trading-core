package com.rltrader.tradingcore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class PortfolioStateTest {

    @Test
    void flatStateHasZeroBarsSinceEntry() {
        PortfolioState flat = PortfolioState.flat(10_000.0);
        assertThat(flat.units()).isZero();
        assertThat(flat.entryBarTs()).isNull();
        assertThat(flat.barsSinceEntry(
                ZonedDateTime.parse("2026-08-06T11:00:00+09:00[Asia/Seoul]"))).isZero();
    }

    @Test
    void heldStateComputesBarsFromEntry() {
        ZonedDateTime entry = ZonedDateTime.parse("2026-08-06T10:05:00+09:00[Asia/Seoul]");
        PortfolioState held = new PortfolioState(2, 0.5, 6_000.0, entry);
        assertThat(held.barsSinceEntry(
                ZonedDateTime.parse("2026-08-06T11:00:00+09:00[Asia/Seoul]"))).isEqualTo(11);
    }

    @Test
    void tradeActionMapping() {
        assertThat(TradeAction.fromIndex(1)).contains(TradeAction.ADD_UNIT);
        assertThat(TradeAction.ADD_UNIT.label()).isEqualTo("add_unit");
        assertThat(TradeAction.fromIndex(3)).isEmpty();
    }
}
