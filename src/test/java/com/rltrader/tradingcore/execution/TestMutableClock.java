package com.rltrader.tradingcore.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 타임아웃 판정(§Task 9)이 clock 기반이므로, 폴링 사이 시간 경과를 테스트가 직접 제어하기 위한
 * Clock 서브클래스. {@link #advance(Duration)}로 시각을 전진시킨다 — 실시간 대기 없이
 * fillTimeoutMs(기본 60s) 도달을 시뮬레이션한다.
 */
final class TestMutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    TestMutableClock(Instant start, ZoneId zone) {
        this.now = new AtomicReference<>(start);
        this.zone = zone;
    }

    void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    /** Task 10 — EOD 경계 테스트가 벽시계를 특정 시각으로 직접 지정하기 위한 헬퍼. */
    void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestMutableClock(now.get(), zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
