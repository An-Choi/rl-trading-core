package com.rltrader.tradingcore.research;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class ResearchLedgerTest {
    ResearchLedger flat() { return new ResearchLedger("session","model",10000,0,0,0); }

    @Test void resumeAcceptsArithmeticRoundoffButRejectsChangedPortfolioOrSession() {
        assertTrue(ResearchDryRun.sameState(flat(),new ResearchLedger("session","model",10000+1e-9,0,0,0)));
        assertFalse(ResearchDryRun.sameState(flat(),new ResearchLedger("session","model",10001,0,0,0)));
        assertFalse(ResearchDryRun.sameState(flat(),new ResearchLedger("other","model",10000,0,0,0)));
        assertFalse(ResearchDryRun.sameState(flat(),new ResearchLedger("session","model",10000,0,0,1)));
        assertFalse(ResearchDryRun.sameState(flat(),new ResearchLedger("session","other",10000,0,0,0)));
        assertFalse(ResearchDryRun.sameState(flat(),new ResearchLedger("session","model",10000,1,100,0)));
    }

    @Test void integerAccountingAndAverageBasisSurviveAddReduceClear() {
        ResearchLedger state=flat();
        assertEquals(40,state.plan(.4,100,.01));
        state=state.fill(40,100,4);
        assertEquals(39,state.plan(.8,100,.01));
        state=state.fill(39,120,4.68);
        assertEquals(-61,state.plan(.2,100,.01));
        state=state.fill(-61,90,5.49);
        assertEquals(8680.*18/79,state.costBasis(),1e-8);
        assertEquals(-18,state.plan(0,100,.05));
        state=state.fill(-18,110,1.98);
        assertEquals(8773.85,state.cash(),1e-8);
        assertEquals(0,state.shares());assertEquals(0,state.costBasis());
    }

    @Test void sizingUsesObservedPriceAndMatchesPythonFloorDivision() {
        assertEquals(60,flat().plan(.6,100,.01));
        assertEquals(16899,ResearchLedger.pythonFloorDivide(1690,.1));
        ResearchLedger held=flat().fill(60,100,6);
        assertEquals(0,held.plan(.6,100,.05));
        assertThrows(IllegalArgumentException.class,()->flat().fill(-1,100,0));
        assertThrows(IllegalArgumentException.class,()->flat().fill(101,100,0));
    }

    @Test void duplicateDecisionAndFutureDiscountCannotIncreasePlannedFill() {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("decision_index",1);value.put("action",3);value.put("target_weight",.6);
        value.put("action_mask",List.of(true,true,true,true,true,true));value.put("observation",List.of(0.));
        value.put("probabilities",List.of(0.,0.,0.,1.,0.,0.));value.put("mark_price",100.);
        value.put("rebalance_tolerance",.01);value.put("requested_quantity",60);
        value.put("events",List.of(Map.of("reason","target","requested_quantity",60,"quantity",120,
            "price",50.,"cost",0.,"fill_available",true)));
        assertThrows(IllegalArgumentException.class,()->ResearchDryRun.apply(flat(),value,1));
        value.put("decision_index",0);
        assertThrows(IllegalArgumentException.class,()->ResearchDryRun.apply(flat(),value,1));
    }

    @Test void remoteUrlsAndLegacyActionSchemasAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new ResearchDryRun("https://example.com"));
        assertThrows(IllegalArgumentException.class,()->ResearchDryRun.handshake(Map.of(
            "api_contract","research-dry-run-v1","research_only",true,"broker_orders_enabled",false,
            "contract",Map.of("version","controlled-exposure-v1","action_schema","incremental-4","n_actions",4))));
    }
}
