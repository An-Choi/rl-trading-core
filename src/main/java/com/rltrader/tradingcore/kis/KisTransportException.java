package com.rltrader.tradingcore.kis;

/** spec §3: 미송신 확실(requestSent=false)만 재전송이 안전하다 — 구분을 타입으로 강제. */
public class KisTransportException extends RuntimeException {
    private final boolean requestSent;

    public KisTransportException(String message, boolean requestSent, Throwable cause) {
        super(message, cause);
        this.requestSent = requestSent;
    }

    public boolean requestSent() { return requestSent; }
}
