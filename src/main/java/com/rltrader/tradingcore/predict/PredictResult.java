package com.rltrader.tradingcore.predict;

public record PredictResult(PredictResponse body, long latencyMs) {}
