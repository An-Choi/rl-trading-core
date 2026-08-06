package com.rltrader.tradingcore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class LedgerPersistenceTest extends AbstractIntegrationTest {

    @Autowired DecisionRepository decisions;

    private static DecisionEntity decision(String symbol, OffsetDateTime barTs) {
        DecisionEntity e = new DecisionEntity();
        e.setSymbol(symbol);
        e.setBarTs(barTs);
        e.setAction(0);
        e.setLabel("hold");
        e.setActionMask("[true, false, false]");
        e.setObservation("[0.1, 0.2]");
        e.setBarClose(71_000.0);
        e.setArtifactId("ppo-fs3-x");
        return e;
    }

    @Test
    void uniqueSymbolBarTsEnforced() {
        OffsetDateTime bar = OffsetDateTime.parse("2026-08-06T11:00:00+09:00");
        decisions.saveAndFlush(decision("005930", bar));
        assertThatThrownBy(() -> decisions.saveAndFlush(decision("005930", bar)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(decisions.count()).isEqualTo(1);
    }
}
