package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.time.ZonedDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * spec §2 가상 체결 — 명시적 근사: 체결가 = 관찰 bar Close, friction 0.
 * 2차의 KisOrderExecutor가 같은 인터페이스를 구현한다 (오케스트레이션은 재설계).
 * {@code @Profile("!live")} — live 컨텍스트에서는 KisOrderExecutor만 ExecutionPort로 뜬다
 * (빈 중복 주입 모호성 차단, Task 12).
 */
@Component
@Profile("!live")
public class DryRunExecutor implements ExecutionPort {

    /** decisionId는 무시한다 — dry-run은 order/decisionId 개념이 없다(기존 로직 무변경, Task 12). */
    @Override
    public FillResult execute(TradeAction action, PortfolioState state,
                              double barClose, ZonedDateTime barTs, ArtifactContext ctx, long decisionId) {
        return switch (action) {
            case HOLD -> new FillResult(state, barClose, 0.0);
            case ADD_UNIT -> {
                double notional = ctx.notional();
                double sharesDelta = notional / barClose;
                ZonedDateTime entry = state.units() == 0 ? barTs : state.entryBarTs();
                yield new FillResult(new PortfolioState(
                        state.units() + 1, state.shares() + sharesDelta,
                        state.cash() - notional, entry), barClose, sharesDelta);
            }
            case CLEAR -> new FillResult(new PortfolioState(
                    0, 0.0, state.cash() + state.shares() * barClose, null),
                    barClose, -state.shares());
        };
    }
}
