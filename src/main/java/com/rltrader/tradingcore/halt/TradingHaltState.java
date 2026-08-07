package com.rltrader.tradingcore.halt;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 런타임 정지 신호(in-memory AtomicBoolean, 프로세스 수명 동안 단방향) — 이 클래스 자체는
 * "왜 멈췄는지"를 구분하지 않는다. 두 가지 서로 다른 halt 종류가 이 하나의 플래그를 공유한다:
 *
 * <ol>
 *   <li><b>영속 HALT</b>(spec §7) — {@link HaltService#raise}가 호출. state.halt()로 즉시
 *       정지시킨 뒤 halts 테이블에 행을 남긴다(best-effort). 재시작해도 halts.cleared_at이
 *       수동으로 채워지기 전까지는(부트 가드 {@link HaltService#assertNoActiveHalt}) 다시
 *       멈춘 채로 기동해야 한다 — "재시작이 안전 정지를 풀 수 없다."
 *   <li><b>재시작-해제형 HALT</b> — 예: {@code DecisionCycleService}의 ARTIFACT_CHANGED.
 *       이 클래스의 halt()만 직접 호출하고 HaltService를 거치지 않는다 — halts 테이블에는
 *       의도적으로 아무것도 기록되지 않는다. 같은 프로세스 안에서는 (이 클래스도 되돌릴 수단이
 *       없으므로) 여전히 되돌릴 수 없지만, 새 JVM(재시작)은 이 빈을 새로 생성하므로 halted=false로
 *       다시 시작한다 — 즉 "재시작하면 풀린다."
 * </ol>
 *
 * <p>두 종류의 구분은 이 클래스가 아니라 halts 테이블 존재 여부로만 이루어진다 — 이 클래스에는
 * 종류를 나타내는 필드나 상태를 추가하지 않는다. 영속성(halts INSERT)은 전적으로
 * {@link HaltService}의 책임이고, 이 클래스는 순수한 런타임 정지 플래그일 뿐이다.
 */
@Component
public class TradingHaltState {

    private final AtomicBoolean halted = new AtomicBoolean(false);

    /**
     * 정지 플래그를 세운다. 이 프로세스 안에서는 되돌릴 수 없다. halts 테이블에 기록할지는
     * 호출부의 몫 — {@link HaltService#raise}를 거치면 영속(재시작해도 유지), 이 메서드를 직접
     * 호출하면 비영속(재시작하면 해제)이다.
     */
    public void halt() {
        halted.set(true);
    }

    /** 정지 여부. */
    public boolean isHalted() {
        return halted.get();
    }
}
