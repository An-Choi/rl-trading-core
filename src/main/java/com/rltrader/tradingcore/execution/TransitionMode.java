package com.rltrader.tradingcore.execution;

/**
 * spec §3 — 전이 검증을 정상 경로/복구 점프 중 어느 표로 판정할지 선택한다.
 * NORMAL: OrderStateMachine.assertTransition (인접 상태만 허용).
 * RECOVERY: OrderStateMachine.assertRecoveryJump (마지막 커밋 상태 → 현재 관찰 상태 전방 직행 허용).
 */
public enum TransitionMode {
    NORMAL, RECOVERY
}
