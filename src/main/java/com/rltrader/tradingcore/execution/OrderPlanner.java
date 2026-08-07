package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * TradeAction → 시장가 주문 계획. 수량은 항상 바닥(floor) — 매수는 notional을 초과하지 않게,
 * 매도는 실제 보유 잔량을 초과해 주문하지 않게 (spec §3).
 */
@Component
public class OrderPlanner {

    public Optional<OrderPlan> plan(TradeAction action, PortfolioState state,
            ArtifactContext ctx, double barClose) {
        return switch (action) {
            case HOLD -> Optional.empty();
            case ADD_UNIT -> {
                long qty = (long) Math.floor(ctx.notional() / barClose);
                yield Optional.of(new OrderPlan(OrderSide.BUY, qty));
            }
            case CLEAR -> {
                long qty = (long) Math.floor(state.shares());
                yield Optional.of(new OrderPlan(OrderSide.SELL, qty));
            }
        };
    }
}
