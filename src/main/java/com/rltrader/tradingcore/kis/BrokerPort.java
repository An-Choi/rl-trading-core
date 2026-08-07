package com.rltrader.tradingcore.kis;

import com.rltrader.tradingcore.domain.OrderSide;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 이후 모든 태스크가 이 계약으로 KIS를 본다. */
public interface BrokerPort {

    /**
     * 성공 시 ack(주문번호). 거부 응답이면 KisOrderRejectedException(rt_cd·msg 포함),
     * 전송 계층 실패면 KisTransportException(requestSent 구분).
     */
    KisOrderAck submitMarketOrder(String symbol, OrderSide side, long qty);

    void cancelOrder(String kisOrderNo, long remainingQty);

    /** 당일 주문 전체 — pagination(tr_cont) 완주. 조회는 재시도 안전. */
    List<KisOrderSnapshot> dailyOrders(LocalDate tradingDate);

    Optional<KisOrderSnapshot> orderSnapshot(String kisOrderNo, LocalDate tradingDate);

    KisBalance balance();
}
