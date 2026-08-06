package com.rltrader.tradingcore.predict;

public record ArtifactContext(
        String artifactId, int featureSchemaVersion,
        double unitFraction, double initialCash, int maxUnits) {

    public double notional() { return initialCash * unitFraction; }
}
