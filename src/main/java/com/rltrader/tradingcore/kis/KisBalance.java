package com.rltrader.tradingcore.kis;

import java.math.BigDecimal;

/** 잔고 조회 응답 — 단일 심볼 보유수량 + 예수금(현금잔고). */
public record KisBalance(long holdingQty, BigDecimal cashBalance) {}
