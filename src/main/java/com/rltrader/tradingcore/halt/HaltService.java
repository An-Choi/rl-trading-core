package com.rltrader.tradingcore.halt;

import com.rltrader.tradingcore.ledger.HaltEntity;
import com.rltrader.tradingcore.ledger.HaltRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * spec §0·§7 영속 HALT — 재시작이 안전 정지를 풀 수 없다. 해제는 명시적 조작(2차: 수동
 * UPDATE halts SET cleared_at=now(), cleared_by='...' WHERE id=...; 운영 API는 3차).
 * 주의: DB 불능 시 halts INSERT는 실패할 수 있다 — 로그(ERROR)가 필수 채널 (1차 §5 알림 계약).
 */
@Component
public class HaltService {

    private static final Logger log = LoggerFactory.getLogger(HaltService.class);

    private final HaltRepository halts;
    private final TradingHaltState state;
    private final ObjectMapper mapper;

    public HaltService(HaltRepository halts, TradingHaltState state, ObjectMapper mapper) {
        this.halts = halts;
        this.state = state;
        this.mapper = mapper;
    }

    /**
     * TradingHaltState.halt()를 무조건 먼저 실행하고, 그 뒤 halts INSERT를 시도한다.
     * DB 불능 시 INSERT 실패를 삼키되 ERROR 로그가 필수 채널(1차 §5 알림 계약과 동일 논리).
     */
    public void raise(String code, Map<String, Object> detail) {
        log.error("[HALT] {} — {} (해제: halts.cleared_at 수동 기록 후 재기동)", code, detail);
        state.halt();   // 영속 실패와 무관하게 정지 먼저
        try {
            HaltEntity h = new HaltEntity();
            h.setCode(code);
            h.setDetail(mapper.writeValueAsString(detail));
            halts.save(h);
        } catch (RuntimeException e) {
            log.error("[HALT] persist failed — 로그가 유일한 기록이다", e);
        }
    }

    /** 미해제 HALT가 있으면 IllegalStateException 발생 (기동 거부용). */
    public void assertNoActiveHalt() {
        var active = halts.findByClearedAtIsNull();
        if (!active.isEmpty()) {
            throw new IllegalStateException("active halt(s) present: "
                    + active.stream().map(HaltEntity::getCode).toList()
                    + " — halts.cleared_at 기록 후 재기동하라 (spec §7)");
        }
    }
}
