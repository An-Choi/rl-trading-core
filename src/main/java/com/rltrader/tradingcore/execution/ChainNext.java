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

    /** 일반(비이월) 체인의 중간 재시도 — liquidationPending은 항상 false. */
    public static ChainNext retry(OrderEntity nextAttempt) {
        return new ChainNext(false, nextAttempt, false);
    }

    /**
     * 재개된 이월(liquidation) 체인의 중간 재시도 (Task 10 리뷰 수정) — {@code retry(nextAttempt)}가
     * liquidationPending을 항상 false로 하드코딩하면, LiquidationService.resume()이 재진입시킨
     * 체인이 그 안에서 다시 취소·재시도(잔량 있음·EOD 아직 아님)로 이어질 때 그 중간 finalization이
     * 이월 플래그를 조용히 꺼버린다 — pendingActive()의 유일한 스캐너가 그 순간부터 이 decision을
     * 더 이상 찾지 못하므로, 그 직후 크래시하면 IN_PROGRESS·미회수 잔량이 발견 경로 없이 영구
     * 유실된다. 이 체인이 이월 재개분임을 아는 호출부(OrderLifecycleService.runChain)가
     * preserveLiquidationPending을 명시적으로 넘겨 재확언한다 — completed finalization
     * (markCompleted())에서만 비로소 false가 된다.
     */
    public static ChainNext retry(OrderEntity nextAttempt, boolean preserveLiquidationPending) {
        return new ChainNext(false, nextAttempt, preserveLiquidationPending);
    }

    /**
     * EOD/진행불가 이월 (Task 10) — decision은 의도 미완이므로 completed=false로 남긴다
     * (execution_status는 IN_PROGRESS 유지). liquidationPending=true만 원자 커밋.
     */
    public static ChainNext liquidation() {
        return new ChainNext(false, null, true);
    }
}
