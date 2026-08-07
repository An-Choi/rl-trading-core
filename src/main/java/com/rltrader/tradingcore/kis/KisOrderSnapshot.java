package com.rltrader.tradingcore.kis;

import java.math.BigDecimal;

/**
 * KIS 조회 응답(일별주문체결·개별주문조회)의 정규화 스냅샷.
 *
 * <p>{@code brokerTerminal}: 브로커 관점에서 더 이상 상태 변화가 없는 종결 상태
 * (잔여수량 0 이거나 취소 확정) — {@code (rmn_qty == 0 || cncl_yn == "Y")}.
 * {@code cancelled}: 취소 확정 여부 — {@code (cncl_yn == "Y")}.
 */
public record KisOrderSnapshot(
        String kisOrderNo,
        long orderQty,
        long cumulativeFilledQty,
        BigDecimal cumulativeAvgPrice,
        boolean brokerTerminal,
        boolean cancelled) {}
