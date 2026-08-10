package com.rltrader.tradingcore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.rltrader.tradingcore.cycle.CycleOutcome;
import com.rltrader.tradingcore.cycle.DecisionCycleService;
import com.rltrader.tradingcore.execution.LiquidationService;
import com.rltrader.tradingcore.ledger.AlertRecorder;
import com.rltrader.tradingcore.ledger.OrderRepository;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DecisionSchedulerTest {

    private static Clock fixedAt(String kstIso) {
        return Clock.fixed(ZonedDateTime.parse(kstIso + "+09:00[Asia/Seoul]").toInstant(),
                com.rltrader.tradingcore.domain.TradingGrid.KST);
    }

    /** dry-run 프로파일 동형 — live 전용 빈이 없는 ObjectProvider(getIfAvailable() == null). */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static DecisionScheduler scheduler(DecisionCycleService cycle, AlertRecorder alerts, Clock clock) {
        return new DecisionScheduler(cycle, alerts, clock, new CycleAdmissionGate(),
                DecisionSchedulerTest.<OrderRepository>emptyProvider(),
                DecisionSchedulerTest.<LiquidationService>emptyProvider());
    }

    @Test
    void firesCycleForGridBar() {
        DecisionCycleService cycle = mock(DecisionCycleService.class);
        when(cycle.isHalted()).thenReturn(false);
        when(cycle.runCycle(any(), any())).thenReturn(CycleOutcome.EXECUTED);
        DecisionScheduler scheduler = scheduler(
                cycle, mock(AlertRecorder.class), fixedAt("2026-08-06T11:00:20"));
        scheduler.enable();
        scheduler.tick();
        verify(cycle).runCycle(
                ZonedDateTime.parse("2026-08-06T11:00:00+09:00[Asia/Seoul]"), null);
    }

    @Test
    void skipsWhenNotEnabledOrHaltedOrOffGrid() {
        DecisionCycleService cycle = mock(DecisionCycleService.class);
        DecisionScheduler notEnabled = scheduler(
                cycle, mock(AlertRecorder.class), fixedAt("2026-08-06T11:00:20"));
        notEnabled.tick();                                       // enable 전 — 무시
        DecisionScheduler offGrid = scheduler(
                cycle, mock(AlertRecorder.class), fixedAt("2026-08-06T15:40:20"));
        offGrid.enable();
        offGrid.tick();                                          // grid 밖 — 무시
        verify(cycle, never()).runCycle(any(), any());
    }

    @Test
    void singleFlightSkipsOverlappingTick() throws Exception {
        DecisionCycleService cycle = mock(DecisionCycleService.class);
        when(cycle.isHalted()).thenReturn(false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(cycle.runCycle(any(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            return CycleOutcome.EXECUTED;
        });
        AlertRecorder alerts = mock(AlertRecorder.class);
        DecisionScheduler scheduler = scheduler(
                cycle, alerts, fixedAt("2026-08-06T11:00:20"));
        scheduler.enable();
        Thread first = new Thread(scheduler::tick);
        first.start();
        entered.await();
        scheduler.tick();                                        // 겹침 — SKIPPED_BUSY
        verify(alerts).record(eq("SKIPPED_BUSY"), any(), any());
        release.countDown();
        first.join();
        verify(cycle, times(1)).runCycle(any(), any());
    }

    @Test
    void unexpectedRuntimeExceptionFromCycleIsAlertedNotPropagated() {
        DecisionCycleService cycle = mock(DecisionCycleService.class);
        when(cycle.isHalted()).thenReturn(false);
        RuntimeException boom = new IllegalStateException("boom");
        when(cycle.runCycle(any(), any())).thenThrow(boom);
        AlertRecorder alerts = mock(AlertRecorder.class);
        DecisionScheduler scheduler = scheduler(
                cycle, alerts, fixedAt("2026-08-06T11:00:20"));
        scheduler.enable();

        scheduler.tick();   // 예외가 스케줄러 스레드로 전파되지 않아야 함

        verify(alerts).record(eq("UNEXPECTED_ERROR"),
                eq(ZonedDateTime.parse("2026-08-06T11:00:00+09:00[Asia/Seoul]")), any());
        // lock이 해제되어 후속 tick이 정상적으로 재진입 가능해야 함
        reset(cycle);
        when(cycle.isHalted()).thenReturn(false);
        when(cycle.runCycle(any(), any())).thenReturn(CycleOutcome.EXECUTED);
        scheduler.tick();
        verify(cycle).runCycle(any(), any());
    }

    @Test
    void smokeBarsStartFromConfiguredBarAndCapAtLastBar() {
        var bars = SmokeRunner.smokeBars(
                java.time.LocalDate.of(2026, 7, 21), java.time.LocalTime.of(10, 5), 6);
        assertThat(bars).hasSize(6);
        assertThat(bars.get(0)).isEqualTo(
                ZonedDateTime.parse("2026-07-21T10:05:00+09:00[Asia/Seoul]"));
        assertThat(bars.get(5)).isEqualTo(
                ZonedDateTime.parse("2026-07-21T10:30:00+09:00[Asia/Seoul]"));
        var tail = SmokeRunner.smokeBars(
                java.time.LocalDate.of(2026, 7, 21), java.time.LocalTime.of(15, 15), 6);
        assertThat(tail).hasSize(2);   // 15:15, 15:20 — 마지막 bar에서 절단
    }
}
