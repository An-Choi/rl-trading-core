package com.rltrader.tradingcore.halt;

import static org.assertj.core.api.Assertions.*;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.ledger.HaltRepository;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class HaltServiceTest extends AbstractIntegrationTest {

    @Autowired HaltRepository halts;
    @Autowired ObjectMapper mapper;

    @Test
    void raise_persists_and_sets_halt_state_then_boot_is_refused() {
        TradingHaltState state = new TradingHaltState();
        HaltService svc = new HaltService(halts, state, mapper);
        svc.raise("RECON_UNEXPLAINED", Map.of("why", "local fill without broker order"));

        assertThat(state.isHalted()).isTrue();
        assertThat(halts.findByClearedAtIsNull()).hasSize(1);
        assertThatThrownBy(svc::assertNoActiveHalt).isInstanceOf(IllegalStateException.class);

        // 수동 해제(2차: SQL) 후 기동 허용
        var h = halts.findByClearedAtIsNull().get(0);
        h.setClearedAt(java.time.OffsetDateTime.now());
        h.setClearedBy("byungju");
        halts.save(h);
        svc.assertNoActiveHalt();   // no exception
    }
}
