package com.rltrader.tradingcore.scheduler;

import com.rltrader.tradingcore.cycle.DecisionCycleService;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.TradingGrid;
import com.rltrader.tradingcore.execution.LiquidationService;
import com.rltrader.tradingcore.ledger.AlertRecorder;
import com.rltrader.tradingcore.ledger.OrderRepository;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 5분 grid 스케줄러 — 단일 인스턴스 전제(replicas=1, spec §1),
 * single-flight는 CycleAdmissionGate(공용 빈, Task 12 — 이전엔 이 클래스의 private lock이었으나
 * Task 13의 주기 대사와 공유하기 위해 분리). 운영 호출은 as_of 항상 생략(null) —
 * smoke는 이 스케줄러를 쓰지 않고 SmokeRunner가 사이클을 직접 구동한다.
 *
 * <p>live 전용 의존(OrderRepository, LiquidationService)은 ObjectProvider로 주입 — dry-run
 * 프로파일에는 이 빈들이 없어도(=live가 아니면 KisOrderExecutor·관련 빈이 없어도) 이 스케줄러가
 * 뜰 수 있어야 한다(빈 그래프 무변경, Task 12).
 */
@Component
public class DecisionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DecisionScheduler.class);
    private static final java.util.Set<OrderStatus> NON_TERMINAL =
            EnumSet.complementOf(EnumSet.of(OrderStatus.FILLED, OrderStatus.CANCELLED,
                    OrderStatus.REJECTED, OrderStatus.ABORTED));

    private final DecisionCycleService cycle;
    private final AlertRecorder alerts;
    private final Clock clock;
    private final CycleAdmissionGate gate;
    private final ObjectProvider<OrderRepository> orders;
    private final ObjectProvider<LiquidationService> liquidation;

    private final AtomicBoolean enabled = new AtomicBoolean(false);

    public DecisionScheduler(DecisionCycleService cycle, AlertRecorder alerts, Clock clock,
            CycleAdmissionGate gate, ObjectProvider<OrderRepository> orders,
            ObjectProvider<LiquidationService> liquidation) {
        this.cycle = cycle;
        this.alerts = alerts;
        this.clock = clock;
        this.gate = gate;
        this.orders = orders;
        this.liquidation = liquidation;
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
        if (!gate.tryAcquire()) {
            alerts.record("SKIPPED_BUSY", bar.get(), Map.of("firedAt", now.toString()));
            return;
        }
        try {
            runGuarded(bar.get());
        } catch (RuntimeException e) {
            // 열거되지 않은 실패 — runCycle 계약상 상태는 이미 무변경. 스케줄러 스레드로
            // 전파하면 알림 없이 죽는 유일한 경로가 되므로(spec §6) 여기서 막는다.
            log.error("[SCHEDULER] unexpected exception from runCycle at bar {}", bar.get(), e);
            alerts.record("UNEXPECTED_ERROR", bar.get(), Map.of("reason", e.toString()));
        } finally {
            gate.release();
        }
    }

    /**
     * grid 발화 시 순서 (Task 12): ① 미종결 주문이 있으면 predict를 호출하지 않고 skip
     * (SKIPPED_ORDER_PENDING) — 복구/대사가 먼저 정리해야 할 상태이므로 새 decision을 얹지
     * 않는다. ② liquidation 이월이 대기 중이면 predict 대신 resume()으로 잔량을 마저 정리한다.
     * ③ 그 외 평상시 runCycle. live 전용 의존이 없는(dry-run) 프로파일에서는 orders/liquidation
     * ObjectProvider가 비어 있으므로 이 분기들이 항상 통과되어 기존 동작과 동일하다.
     */
    private void runGuarded(ZonedDateTime bar) {
        OrderRepository orderRepo = orders.getIfAvailable();
        if (orderRepo != null && !orderRepo.findByStatusIn(NON_TERMINAL).isEmpty()) {
            alerts.record("SKIPPED_ORDER_PENDING", bar, Map.of());
            return;
        }
        LiquidationService liq = liquidation.getIfAvailable();
        if (liq != null && liq.pendingActive()) {
            liq.resume(ZonedDateTime.now(clock));
            return;
        }
        cycle.runCycle(bar, null);   // 운영은 as_of 항상 생략 (계약)
    }
}
