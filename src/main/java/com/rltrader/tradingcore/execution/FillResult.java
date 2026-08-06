package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.PortfolioState;

public record FillResult(PortfolioState newState, double fillPrice, double sharesDelta) {}
