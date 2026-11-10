package com.rltrader.tradingcore.research;

/** Independent integer-share accounting for the six-target research protocol. */
public record ResearchLedger(String sessionId, String modelSha256, double cash,
                             long shares, double costBasis, long decisions) {
    public ResearchLedger {
        if (sessionId == null || modelSha256 == null || !Double.isFinite(cash)
                || !Double.isFinite(costBasis) || cash < -1e-5 || shares < 0
                || costBasis < -1e-5 || decisions < 0 || (shares == 0 && costBasis != 0)) {
            throw new IllegalArgumentException("invalid research portfolio");
        }
    }

    public ResearchLedger fill(long quantity, double price, double cost) {
        if (!Double.isFinite(price) || price <= 0 || !Double.isFinite(cost) || cost < 0
                || (quantity == 0 && cost != 0)) {
            throw new IllegalArgumentException("invalid research fill");
        }
        long nextShares = Math.addExact(shares, quantity);
        if (nextShares < 0) throw new IllegalArgumentException("short position");
        double nextCash = cash - (quantity * price + cost);
        double basis = quantity > 0 ? costBasis + quantity * price
                : (shares == 0 ? 0 : costBasis * ((double) nextShares / shares));
        if (nextShares == 0) basis = 0;
        return new ResearchLedger(sessionId, modelSha256, nextCash, nextShares, basis, decisions);
    }

    public ResearchLedger nextDecision() {
        return new ResearchLedger(sessionId, modelSha256, cash, shares, costBasis, decisions + 1);
    }

    /** Match Python's positive float floor division, including quotient rounding. */
    static long pythonFloorDivide(double numerator, double denominator) {
        double quotient = (numerator - numerator % denominator) / denominator;
        double floor = Math.floor(quotient);
        if (quotient - floor > .5) floor += 1;
        if (!Double.isFinite(floor) || floor < 0 || floor > Long.MAX_VALUE)
            throw new IllegalArgumentException("invalid planned quantity");
        return (long) floor;
    }

    public long plan(double target, double observedPrice, double tolerance) {
        if (!Double.isFinite(target) || target < 0 || target > 1
                || !Double.isFinite(observedPrice) || observedPrice <= 0
                || !Double.isFinite(tolerance) || tolerance < 0 || tolerance >= .1)
            throw new IllegalArgumentException("invalid target plan");
        double equity = cash + shares * observedPrice;
        double exposure = shares * observedPrice / Math.max(equity, 1e-9);
        if (target != 0 && Math.abs(target - exposure) <= tolerance) return 0;
        return pythonFloorDivide(target * equity, observedPrice) - shares;
    }
}
