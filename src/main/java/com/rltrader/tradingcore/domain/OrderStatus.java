package com.rltrader.tradingcore.domain;

import java.util.Set;

public enum OrderStatus {
    NEW, SUBMITTED, SUBMIT_UNKNOWN, ACKED, PARTIALLY_FILLED, CANCEL_PENDING,
    FILLED, CANCELLED, REJECTED, ABORTED;

    private static final Set<OrderStatus> TERMINAL = Set.of(FILLED, CANCELLED, REJECTED, ABORTED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
