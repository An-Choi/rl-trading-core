package com.rltrader.tradingcore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskGateTest {

    private static final TradingProperties PROPS = new TradingProperties(
            "005930", "http://x", 20, 5, 10_000.0, 10_000.0, 5000, 240_000, 5000, 60);
    private static final List<Boolean> ALL_TRUE = List.of(true, true, true);

    @Test
    void actionMaskedFalseIsViolation() {
        ArtifactContext ctx = new ArtifactContext("a", 3, 0.199, 10_000.0, 5);
        assertThat(RiskGate.check(TradeAction.ADD_UNIT, List.of(true, false, true),
                PortfolioState.flat(10_000.0), ctx, PROPS)).isPresent();
    }

    @Test
    void effectiveMaxUnitsIsMinOfArtifactAndCap() {
        // artifact max_units=3 < cap=5 — 서버 mask 버그로 4번째 Add가 True여도 거부해야 함
        ArtifactContext ctx = new ArtifactContext("a", 3, 0.199, 10_000.0, 3);
        PortfolioState atArtifactLimit = new PortfolioState(3, 0.1, 4_000.0, null);
        assertThat(RiskGate.check(TradeAction.ADD_UNIT, ALL_TRUE, atArtifactLimit, ctx, PROPS))
                .isPresent();
    }

    @Test
    void capitalCapBlocksAdd() {
        // (units+1) × notional = 3 × 1990 = 5970 ≤ 10000 통과, cash 부족이면 거부
        ArtifactContext ctx = new ArtifactContext("a", 3, 0.199, 10_000.0, 5);
        PortfolioState lowCash = new PortfolioState(2, 0.1, 100.0, null);
        assertThat(RiskGate.check(TradeAction.ADD_UNIT, ALL_TRUE, lowCash, ctx, PROPS))
                .isPresent();
    }

    @Test
    void holdAndClearPassWhenMasked() {
        ArtifactContext ctx = new ArtifactContext("a", 3, 0.199, 10_000.0, 5);
        PortfolioState held = new PortfolioState(1, 0.1, 8_000.0, null);
        assertThat(RiskGate.check(TradeAction.HOLD, ALL_TRUE, held, ctx, PROPS)).isEmpty();
        assertThat(RiskGate.check(TradeAction.CLEAR, ALL_TRUE, held, ctx, PROPS)).isEmpty();
    }

    @Test
    void maskLengthGuardRejectsIncorrectSize() {
        ArtifactContext ctx = new ArtifactContext("a", 3, 0.199, 10_000.0, 5);
        PortfolioState state = PortfolioState.flat(10_000.0);
        // mask 길이 2 — 위반
        assertThat(RiskGate.check(TradeAction.ADD_UNIT, List.of(true, false), state, ctx, PROPS))
                .isPresent();
    }
}
