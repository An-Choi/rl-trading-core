package com.rltrader.tradingcore.scheduler;

import com.rltrader.tradingcore.cycle.DecisionCycleService;
import com.rltrader.tradingcore.domain.TradingGrid;
import com.rltrader.tradingcore.ledger.AlertRecorder;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 5분 grid 스케줄러 — 단일 인스턴스 전제(replicas=1, spec §1),
 * single-flight는 프로세스 내 lock. 운영 호출은 as_of 항상 생략(null) —
 * smoke는 이 스케줄러를 쓰지 않고 SmokeRunner가 사이클을 직접 구동한다.
 */
@Component
public class DecisionScheduler {

    private final DecisionCycleService cycle;
    private final AlertRecorder alerts;
    private final Clock clock;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final ReentrantLock inFlight = new ReentrantLock();

    public DecisionScheduler(DecisionCycleService cycle, AlertRecorder alerts, Clock clock) {
        this.cycle = cycle;
        this.alerts = alerts;
        this.clock = clock;
    }

    public void enable() { enabled.set(true); }

    // 트리거 등록은 SchedulingConfig — cron 초 필드를 delay-seconds에서 구성한다.
    // cron이 9-15시 전체를 발화해도 barLabelFor가 grid 밖(15:25~)·주말을 걸러낸다.
    public void tick() {
        if (!enabled.get() || cycle.isHalted()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        Optional<ZonedDateTime> bar = TradingGrid.barLabelFor(now);
        if (bar.isEmpty()) {
            return;
        }
        if (!inFlight.tryLock()) {
            alerts.record("SKIPPED_BUSY", bar.get(), Map.of("firedAt", now.toString()));
            return;
        }
        try {
            cycle.runCycle(bar.get(), null);   // 운영은 as_of 항상 생략 (계약)
        } finally {
            inFlight.unlock();
        }
    }
}
