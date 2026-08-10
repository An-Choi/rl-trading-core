package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.time.ZonedDateTime;

public interface ExecutionPort {
    FillResult execute(TradeAction action, PortfolioState state,
                       double barClose, ZonedDateTime barTs, ArtifactContext ctx, long decisionId);
}
