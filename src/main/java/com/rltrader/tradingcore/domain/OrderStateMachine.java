package com.rltrader.tradingcore.domain;

import static com.rltrader.tradingcore.domain.OrderStatus.*;

import java.util.Map;
import java.util.Set;

/**
 * spec §3 — 전이는 여기서만 판정한다. 정상 경로와 복구 점프를 구분:
 * 복구는 "DB의 마지막 커밋 상태 → 현재 KIS 관찰 상태" 전방 직행을 허용한다.
 * 돈이 걸린 상태 전이에서 "조용히 이상해짐"이 최악 — 불법 전이는 즉시 예외.
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> NORMAL = Map.of(
        NEW,              Set.of(SUBMITTED, SUBMIT_UNKNOWN, ABORTED),
        SUBMITTED,        Set.of(ACKED, REJECTED),
        SUBMIT_UNKNOWN,   Set.of(ACKED),
        ACKED,            Set.of(PARTIALLY_FILLED, FILLED, CANCEL_PENDING),
        PARTIALLY_FILLED, Set.of(PARTIALLY_FILLED, FILLED, CANCEL_PENDING),
        CANCEL_PENDING,   Set.of(CANCEL_PENDING, CANCELLED, FILLED));

    /**
     * 복구·대사 점프 허용표 — rank 비교가 아니라 "그 상태에서 KIS 관찰로 도달 가능한 상태"를
     * 명시한다. 예: 체결이 있었던 주문(PARTIALLY_FILLED)이 REJECTED로 관찰될 수는 없고,
     * ack된 주문이 ABORTED(미송신)일 수는 없다. ABORTED는 점프 목적지가 아니다 —
     * 현재 프로세스가 requestSent=false를 직접 관찰한 경우에만 정상 전이(NEW→ABORTED)로 간다.
     * 점프 판정만 여기서 하고, terminal 저장은 항상 finalization 경로가 소유한다.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> RECOVERY = Map.of(
        NEW,              Set.of(ACKED, PARTIALLY_FILLED, FILLED, CANCELLED),
        SUBMITTED,        Set.of(ACKED, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED),
        SUBMIT_UNKNOWN,   Set.of(ACKED, PARTIALLY_FILLED, FILLED, CANCELLED),
        ACKED,            Set.of(PARTIALLY_FILLED, FILLED, CANCELLED),
        PARTIALLY_FILLED, Set.of(FILLED, CANCELLED),
        CANCEL_PENDING,   Set.of(FILLED, CANCELLED));

    private OrderStateMachine() {}

    public static void assertTransition(OrderStatus from, OrderStatus to) {
        if (!NORMAL.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalOrderTransitionException(from, to, "normal");
        }
    }

    public static void assertRecoveryJump(OrderStatus from, OrderStatus to) {
        if (!RECOVERY.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalOrderTransitionException(from, to, "recovery");
        }
    }
}
