package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.OrderSide;

/** OrderPlanner.plan()의 산출물 — 제출할 시장가 주문의 side/qty. */
public record OrderPlan(OrderSide side, long qty) {}
