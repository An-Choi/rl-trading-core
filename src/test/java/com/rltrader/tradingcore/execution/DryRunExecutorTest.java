package com.rltrader.tradingcore.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class DryRunExecutorTest {

    private static final ArtifactContext CTX = new ArtifactContext("a", 3, 0.199, 10_000.0, 5);
    private static final ZonedDateTime BAR =
            ZonedDateTime.parse("2026-08-06T11:00:00+09:00[Asia/Seoul]");
    private final DryRunExecutor executor = new DryRunExecutor();

    @Test
    void firstAddSetsEntryBarTs() {
        FillResult r = executor.execute(TradeAction.ADD_UNIT,
                PortfolioState.flat(10_000.0), 71_000.0, BAR, CTX);
        assertThat(r.newState().units()).isEqualTo(1);
        assertThat(r.newState().shares()).isCloseTo(1_990.0 / 71_000.0, offset(1e-12));
        assertThat(r.newState().cash()).isCloseTo(10_000.0 - 1_990.0, offset(1e-9));
        assertThat(r.newState().entryBarTs()).isEqualTo(BAR);   // 최초 Add — 진입 시점 설정
    }

    @Test
    void secondAddKeepsEntryBarTs() {
        ZonedDateTime entry = BAR.minusMinutes(30);
        PortfolioState held = new PortfolioState(1, 0.028, 8_010.0, entry);
        FillResult r = executor.execute(TradeAction.ADD_UNIT, held, 71_000.0, BAR, CTX);
        assertThat(r.newState().units()).isEqualTo(2);
        assertThat(r.newState().entryBarTs()).isEqualTo(entry); // 추가 Add — 유지
    }

    @Test
    void holdChangesNothing() {
        PortfolioState held = new PortfolioState(1, 0.028, 8_010.0, BAR.minusMinutes(30));
        FillResult r = executor.execute(TradeAction.HOLD, held, 71_000.0, BAR, CTX);
        assertThat(r.newState()).isEqualTo(held);
        assertThat(r.sharesDelta()).isZero();
    }

    @Test
    void clearLiquidatesAndResetsEntry() {
        PortfolioState held = new PortfolioState(2, 0.056, 6_020.0, BAR.minusMinutes(30));
        FillResult r = executor.execute(TradeAction.CLEAR, held, 71_000.0, BAR, CTX);
        assertThat(r.newState().units()).isZero();
        assertThat(r.newState().shares()).isZero();
        assertThat(r.newState().cash()).isCloseTo(6_020.0 + 0.056 * 71_000.0, offset(1e-9));
        assertThat(r.newState().entryBarTs()).isNull();          // 진입 시점 초기화
    }
}
