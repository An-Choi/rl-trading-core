package com.rltrader.tradingcore.reconcile;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PERIODIC 대사 트리거 (spec §6) — {@code @Scheduled(fixedDelayString)} + {@code @Profile("live")}.
 *
 * <p>별도 클래스로 분리한 이유: {@code @Profile}은 타입 레벨(@Component/@Configuration) 또는
 * {@code @Bean} 팩토리 메서드에서만 컨테이너가 평가한다 — {@code @Scheduled} 같은 일반 인스턴스
 * 메서드에 얹으면 {@code ScheduledAnnotationBeanPostProcessor}가 조건을 전혀 참조하지 않으므로
 * 조용히 무시되고 모든 프로파일에서 스케줄된다. 브리프는 "SchedulingConfig.java(live 프로파일
 * fixedDelay 등록)"를 지시했지만 SchedulingConfig 자체는 dry-run cron 등록도 겸하는 비-profile
 * 빈이라 그 클래스에 얹으면 이 버그를 그대로 재현한다 — 그래서 타입 레벨 {@code @Profile("live")}가
 * 실제로 걸리는 이 전용 컴포넌트로 옮겼다(동작 의도는 브리프와 동일, 배치만 다르다).
 *
 * <p>{@code fixedDelayString}은 이전 실행 완료 후부터 간격을 재는 방식 — reconcile()이
 * CycleAdmissionGate를 못 얻어 즉시 반환하는 경우를 포함해 실행 사이 최소 간격을 보장한다.
 * {@code initialDelayString}도 같은 간격으로 명시한다 — 지정하지 않으면 Spring은 컨텍스트
 * 기동 직후 0지연으로 첫 실행을 태우는데, "기동 직후 대사"는 이 태스크가 정의하는 별도 트리거
 * (STARTUP, Task 14/2단계 예정)의 몫이지 PERIODIC이 암묵적으로 겸할 계약이 아니다.
 */
@Component
@Profile("live")
public class PeriodicReconciliationTrigger {

    private final ReconciliationService reconciliation;

    public PeriodicReconciliationTrigger(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${order.recon-interval-ms}",
            initialDelayString = "${order.recon-interval-ms}")
    public void tick() {
        reconciliation.reconcile(Trigger.PERIODIC);
    }
}
