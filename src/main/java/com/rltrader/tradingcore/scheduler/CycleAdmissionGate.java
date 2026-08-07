package com.rltrader.tradingcore.scheduler;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * 사이클 단일 진행(single-flight) 공용 게이트 (Task 12) — 이전엔 DecisionScheduler의 private
 * ReentrantLock이었으나, Task 13의 주기 대사(reconciliation)가 사이클 진행 중 스스로를 skip하려면
 * 같은 lock을 공유해야 한다. tryAcquire()가 false면 이미 다른 사이클(또는 대사)이 진행 중 —
 * 호출부가 SKIPPED_BUSY 등으로 처리한다. release()는 반드시 finally에서 호출.
 */
@Component
public class CycleAdmissionGate {

    private final ReentrantLock inFlight = new ReentrantLock();

    public boolean tryAcquire() {
        return inFlight.tryLock();
    }

    public void release() {
        inFlight.unlock();
    }
}
