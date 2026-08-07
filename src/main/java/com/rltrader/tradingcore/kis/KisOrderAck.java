package com.rltrader.tradingcore.kis;

/** 주문 제출 성공 응답 — KIS가 부여한 주문번호. */
public record KisOrderAck(String kisOrderNo) {}
