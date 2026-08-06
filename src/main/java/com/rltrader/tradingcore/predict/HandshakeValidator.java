package com.rltrader.tradingcore.predict;

import com.rltrader.tradingcore.config.TradingProperties;
import java.util.List;
import java.util.Map;

/** spec §4 핸드셰이크 검증 — 위반 = 기동 중단. 운영 한도는 항상 Spring config 소유. */
public final class HandshakeValidator {

    private static final List<String> EXPECTED_LABELS = List.of("hold", "add_unit", "clear");

    private HandshakeValidator() {}

    public static ArtifactContext validate(MetadataResponse meta, TradingProperties props) {
        Object labels = meta.actionSpace().get("labels");
        if (!EXPECTED_LABELS.equals(labels)) {
            throw new IllegalStateException("artifact action labels != " + EXPECTED_LABELS + ": " + labels);
        }
        double unitFraction = finitePositive(meta.envParams(), "unit_fraction");
        double initialCash = finitePositive(meta.envParams(), "initial_cash");
        int maxUnits = positiveInt(meta.envParams(), "max_units");
        if (unitFraction > 1.0) {
            throw new IllegalStateException("unit_fraction must be in (0, 1]: " + unitFraction);
        }
        if (maxUnits > props.maxUnitsCap()) {
            throw new IllegalStateException(
                    "artifact max_units " + maxUnits + " > maxUnitsCap " + props.maxUnitsCap());
        }
        double notional = initialCash * unitFraction;
        if (notional * maxUnits > props.maxCapitalCap()) {
            throw new IllegalStateException("full position " + (notional * maxUnits)
                    + " exceeds capital cap " + props.maxCapitalCap());
        }
        return new ArtifactContext(meta.artifactId(), meta.featureSchemaVersion(),
                unitFraction, initialCash, maxUnits);
    }

    private static double finitePositive(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof Number n) || value instanceof Boolean) {
            throw new IllegalStateException(key + " must be numeric: " + value);
        }
        double d = n.doubleValue();
        if (!Double.isFinite(d) || d <= 0) {
            throw new IllegalStateException(key + " must be finite positive: " + d);
        }
        return d;
    }

    private static int positiveInt(Map<String, Object> params, String key) {
        double d = finitePositive(params, key);
        if (d != Math.floor(d)) {
            throw new IllegalStateException(key + " must be an integer: " + d);
        }
        return (int) d;
    }
}
