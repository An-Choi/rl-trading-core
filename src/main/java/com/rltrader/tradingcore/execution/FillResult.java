package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.PortfolioState;

/**
 * hadTimeout: ChainOutcome.hadTimeout의 ExecutionPort 경계 투영(Task 13) — LiveCycleOrchestrator는
 * OrderLifecycleService/ChainOutcome을 직접 보지 않고 KisOrderExecutor(ExecutionPort 구현체)만
 * 소비하므로, POST_ORDER 강제 대사 트리거 판정에 필요한 이 플래그가 FillResult를 통해 경계를
 * 넘어온다. dry-run(다른 ExecutionPort 구현체)은 fill-timeout 개념이 없으므로 항상 false —
 * 3-인자 보조 생성자로 기존 호출부(DryRunExecutor, KisOrderExecutor의 HOLD 분기)를 그대로 둔다.
 */
public record FillResult(PortfolioState newState, double fillPrice, double sharesDelta, boolean hadTimeout) {

    public FillResult(PortfolioState newState, double fillPrice, double sharesDelta) {
        this(newState, fillPrice, sharesDelta, false);
    }
}
