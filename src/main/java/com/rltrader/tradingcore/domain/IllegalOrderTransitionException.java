package com.rltrader.tradingcore.domain;

public class IllegalOrderTransitionException extends RuntimeException {
    public IllegalOrderTransitionException(OrderStatus from, OrderStatus to, String mode) {
        super("illegal %s transition %s -> %s".formatted(mode, from, to));
    }
}
