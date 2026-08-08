package com.rltrader.tradingcore.reconcile;

/**
 * 대사 진입 사유 (spec §6). PERIODIC만 CycleAdmissionGate 게이트를 탄다 — 나머지는 이 태스크
 * 범위 밖 호출자를 위한 값만 정의(POST_ORDER는 이 태스크가 배선, STARTUP/RECONNECT는 Task 14/2단계).
 */
public enum Trigger {
    PERIODIC, POST_ORDER, STARTUP, RECONNECT
}
