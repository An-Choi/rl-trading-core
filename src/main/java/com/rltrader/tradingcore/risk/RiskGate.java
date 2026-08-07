package com.rltrader.tradingcore.risk;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.util.List;
import java.util.Optional;

/** spec §2 검증 ④ — 운영 한도는 Spring config 소유, artifact 한도가 더 작으면 그것도 지킨다. */
public final class RiskGate {

    private RiskGate() {}

    public static Optional<String> check(TradeAction action, List<Boolean> mask,
            PortfolioState state, ArtifactContext ctx, TradingProperties props) {
        if (mask == null || mask.size() != 3) {
            return Optional.of("action_mask size must be 3, got "
                    + (mask == null ? "null" : mask.size()));   // 1차 인계 노트: 호출 경로 독립 방어
        }
        if (!Boolean.TRUE.equals(mask.get(action.index()))) {
            return Optional.of("action " + action + " masked false");
        }
        if (action == TradeAction.ADD_UNIT) {
            int effectiveMaxUnits = Math.min(ctx.maxUnits(), props.maxUnitsCap());
            if (state.units() >= effectiveMaxUnits) {
                return Optional.of("units " + state.units() + " >= effectiveMaxUnits " + effectiveMaxUnits);
            }
            double totalAfter = (state.units() + 1) * ctx.notional();
            if (totalAfter > props.maxCapitalCap()) {
                return Optional.of("capital after add " + totalAfter + " > cap " + props.maxCapitalCap());
            }
            if (state.cash() < ctx.notional()) {
                return Optional.of("cash " + state.cash() + " < notional " + ctx.notional());
            }
        }
        return Optional.empty();
    }
}
