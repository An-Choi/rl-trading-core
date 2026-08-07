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

    public static ChainNext liquidation() {
        return new ChainNext(true, null, true);
    }
}
