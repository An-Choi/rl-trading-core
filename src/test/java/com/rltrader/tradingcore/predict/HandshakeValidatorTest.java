package com.rltrader.tradingcore.predict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rltrader.tradingcore.config.TradingProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandshakeValidatorTest {

    private static final TradingProperties PROPS = new TradingProperties(
            "005930", "http://x", 20, 5, 10_000.0, 10_000.0, 5000, 240_000, 5000, 60);

    private static MetadataResponse meta(Object unitFraction, Object initialCash, Object maxUnits) {
        return new MetadataResponse("ppo-fs3-x", 3,
                Map.of("type", "discrete", "n", 3, "labels", List.of("hold", "add_unit", "clear")),
                Map.of("unit_fraction", unitFraction, "initial_cash", initialCash,
                       "max_units", maxUnits, "episode_days", 1,
                       "duration_horizon_bars", 1280, "nominal_bars_per_day", 64),
                "abc1234");
    }

    @Test
    void validMetadataProducesContext() {
        ArtifactContext ctx = HandshakeValidator.validate(meta(0.199, 10_000.0, 5), PROPS);
        assertThat(ctx.artifactId()).isEqualTo("ppo-fs3-x");
        assertThat(ctx.notional()).isEqualTo(0.199 * 10_000.0);
    }

    @Test
    void artifactMaxUnitsAboveCapRejected() {
        assertThatThrownBy(() -> HandshakeValidator.validate(meta(0.199, 10_000.0, 6), PROPS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max_units");
    }

    @Test
    void unitFractionOutOfRangeRejected() {
        assertThatThrownBy(() -> HandshakeValidator.validate(meta(0.0, 10_000.0, 5), PROPS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HandshakeValidator.validate(meta(1.5, 10_000.0, 5), PROPS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HandshakeValidator.validate(meta(-0.1, 10_000.0, 5), PROPS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonFiniteOrNonNumericRejected() {
        assertThatThrownBy(() -> HandshakeValidator.validate(meta(Double.NaN, 10_000.0, 5), PROPS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HandshakeValidator.validate(meta("0.199", 10_000.0, 5), PROPS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fullPositionAboveCapitalCapRejected() {
        // notional × max_units = 0.5 × 10000 × 5 = 25000 > maxCapitalCap 10000
        assertThatThrownBy(() -> HandshakeValidator.validate(meta(0.5, 10_000.0, 5), PROPS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("capital");
    }

    @Test
    void wrongActionLabelsRejected() {
        MetadataResponse bad = new MetadataResponse("a", 3,
                Map.of("type", "discrete", "n", 3, "labels", List.of("hold", "clear", "add_unit")),
                Map.of("unit_fraction", 0.199, "initial_cash", 10_000.0, "max_units", 5),
                "sha");
        assertThatThrownBy(() -> HandshakeValidator.validate(bad, PROPS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("labels");
    }
}
