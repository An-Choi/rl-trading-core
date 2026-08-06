package com.rltrader.tradingcore.domain;

import java.util.Arrays;
import java.util.Optional;

public enum TradeAction {
    HOLD(0, "hold"), ADD_UNIT(1, "add_unit"), CLEAR(2, "clear");

    private final int index;
    private final String label;

    TradeAction(int index, String label) {
        this.index = index;
        this.label = label;
    }

    public int index() { return index; }

    public String label() { return label; }

    public static Optional<TradeAction> fromIndex(int index) {
        return Arrays.stream(values()).filter(a -> a.index == index).findFirst();
    }
}
