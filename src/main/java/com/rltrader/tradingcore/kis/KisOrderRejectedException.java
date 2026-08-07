package com.rltrader.tradingcore.kis;

/** KIS가 주문을 받아 처리했으나 거부한 경우(rt_cd != "0") — 전송 실패(KisTransportException)와 구분. */
public class KisOrderRejectedException extends RuntimeException {
    private final String rtCd;
    private final String msg;

    public KisOrderRejectedException(String rtCd, String msg) {
        super("KIS order rejected rt_cd=" + rtCd + " msg=" + msg);
        this.rtCd = rtCd;
        this.msg = msg;
    }

    public String rtCd() { return rtCd; }

    public String msg() { return msg; }
}
