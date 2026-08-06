package com.rltrader.tradingcore.predict;

public class PredictFailureException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public PredictFailureException(String code, int httpStatus, String message) {
        super("[" + code + "] " + message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }

    public int httpStatus() { return httpStatus; }
}
