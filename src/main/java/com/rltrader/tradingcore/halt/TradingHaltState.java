package com.rltrader.tradingcore.halt;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Persistent halt state — 재시작이 안전 정지를 풀 수 없다. spec §7.
 * 정지 자체는 in-memory(AtomicBoolean)이고, 영속성은 HaltService.halts INSERT에서 담당.
 */
@Component
public class TradingHaltState {

    private final AtomicBoolean halted = new AtomicBoolean(false);

    /** 정지 플래그를 세운다. 되돌릴 수 없다(재시작 필요). */
    public void halt() {
        halted.set(true);
    }

    /** 정지 여부. */
    public boolean isHalted() {
        return halted.get();
    }
}
