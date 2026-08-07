package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.ledger.OrderEntity;

/**
 * finalizeChain이 같은 tx에서 커밋할 "체인 다음 상태" — decision.execution_status 갱신,
 * 후속 attempt(NEW) 저장, liquidation_pending 전파 중 무엇을 할지 (spec §2 체인 원자성).
 * Task 8 범위는 completed()만 사용 — retry/liquidation은 Task 9~11이 확장.
 */
public record ChainNext(boolean completed, OrderEntity nextAttempt, boolean liquidationPending) {

    public static ChainNext markCompleted() {
        return new ChainNext(true, null, false);
    }

    public static ChainNext retry(OrderEntity nextAttempt) {
        return new ChainNext(false, nextAttempt, false);
    }

    /**
     * EOD/진행불가 이월 (Task 10) — decision은 의도 미완이므로 completed=false로 남긴다
     * (execution_status는 IN_PROGRESS 유지). liquidationPending=true만 원자 커밋.
     */
    public static ChainNext liquidation() {
        return new ChainNext(false, null, true);
    }
}
