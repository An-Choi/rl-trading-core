package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * live 실거래 ExecutionPort (Task 12) — OrderPlanner로 side/qty를 계획하고
 * OrderLifecycleService.runChain으로 실제 주문 체인을 태운다. HOLD(plan 없음)는 무변경
 * FillResult를 그대로 반환한다.
 *
 * <p>tx1(decisions+orders NEW 저장)은 이 클래스가 아니라 LiveCycleOrchestrator가 소유한다
 * (spec §2 "기록 먼저, 제출 나중") — decisionId는 이미 커밋된 값을 호출부가 넘겨준다.
 * ChainOutcome.after는 DB 커밋값의 double 투영이므로, 이 메서드가 반환하는 FillResult도
 * 그 투영을 그대로 옮긴다(추가 계산 없음) — fillPrice는 평균 체결가(총 notional/총 qty,
 * 0체결이면 barClose로 대체), sharesDelta는 before 대비 after의 차분.
 */
@Component
@Profile("live")
public class KisOrderExecutor implements ExecutionPort {

    private final OrderPlanner planner;
    private final OrderLifecycleService lifecycle;

    public KisOrderExecutor(OrderPlanner planner, OrderLifecycleService lifecycle) {
        this.planner = planner;
        this.lifecycle = lifecycle;
    }

    @Override
    public FillResult execute(TradeAction action, PortfolioState state, double barClose,
            ZonedDateTime barTs, ArtifactContext ctx, long decisionId) {
        return planner.plan(action, state, ctx, barClose)
                .map(plan -> runAndProject(plan, decisionId, barTs, state, ctx, barClose))
                .orElseGet(() -> new FillResult(state, barClose, 0.0));
    }

    private FillResult runAndProject(OrderPlan plan, long decisionId, ZonedDateTime barTs,
            PortfolioState before, ArtifactContext ctx, double barClose) {
        ChainOutcome outcome = lifecycle.runChain(decisionId, plan, barTs, before, ctx);
        double fillPrice = outcome.totalFilledQty() > 0
                ? outcome.totalFilledNotional().divide(BigDecimal.valueOf(outcome.totalFilledQty()),
                        java.math.MathContext.DECIMAL64).doubleValue()
                : barClose;
        double sharesDelta = outcome.after().shares() - before.shares();
        return new FillResult(outcome.after(), fillPrice, sharesDelta);
    }
}
