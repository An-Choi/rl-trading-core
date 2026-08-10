package com.rltrader.tradingcore.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.ledger.*;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Task 8 fix — RECOVERY 모드로 finalizeChain을 호출했을 때(대사·복구가 신규 snapshot을
 * INSERT하는 경로) artifact provenance가 **decision의 artifact_id**를 기록해야 한다
 * (ctx는 현재 프로세스가 로드한 아티팩트일 뿐, 복구 대상 decision의 아티팩트와 다를 수 있음).
 * RECOVERY 호출자(ReconciliationService)는 Task 11까지 없으므로 OrderFinalizationService를
 * 직접 호출하는 focused 유닛 스타일 테스트로 커버한다.
 */
@SpringBootTest
class OrderFinalizationRecoveryProvenanceTest extends AbstractIntegrationTest {

    @Autowired OrderFinalizationService finalization;
    @Autowired OrderRepository orders;
    @Autowired DecisionRepository decisions;
    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired OrderEventRepository orderEvents;
    @Autowired ExecutionRepository executions;

    // ctx는 "현재 프로세스가 로드한" 아티팩트 — decision의 artifact_id와 일부러 다르게 둔다.
    private static final ArtifactContext CURRENT_PROCESS_CTX =
            new ArtifactContext("current-process-artifact", 3, 0.199, 10_000.0, 5);
    private static final String DECISION_ARTIFACT_ID = "recovered-decision-artifact";

    @BeforeEach
    void cleanUp() {
        orderEvents.deleteAll();
        executions.deleteAll();
        orders.deleteAll();
        decisions.deleteAll();
        snapshots.deleteAll();
    }

    @Test
    void recovery_insert_uses_decisions_artifact_id_not_current_process_ctx() {
        DecisionEntity d = new DecisionEntity();
        d.setSymbol("005930");
        d.setBarTs(java.time.OffsetDateTime.now());
        d.setAction(1);
        d.setLabel("add_unit");
        d.setActionMask("[]");
        d.setObservation("[]");
        d.setBarClose(100.0);
        d.setArtifactId(DECISION_ARTIFACT_ID);
        d.setExecutionStatus("IN_PROGRESS");
        decisions.save(d);

        OrderEntity o = new OrderEntity();
        o.setDecisionId(d.getId());
        o.setAttempt(1);
        o.setTradingDate(LocalDate.of(2026, 8, 7));
        o.setSymbol("005930");
        o.setSide(OrderSide.BUY);
        o.setQty(10L);
        o.setOrderType("MARKET");
        o.setStatus(OrderStatus.NEW);
        o.setAccountedFillNotional(BigDecimal.ZERO);
        orders.save(o);

        assertThat(snapshots.findById("005930")).isEmpty();   // 신규 INSERT 경로임을 보장

        KisOrderSnapshot confirmed = new KisOrderSnapshot(
                "9999", 10, 10, new BigDecimal("101.5"), true, false);
        PortfolioState after = new PortfolioState(1, 10.0, 8985.00,
                TestFixtures.bar("2026-08-07T09:35"));

        // NEW→FILLED는 RECOVERY 점프표에서만 허용(정상 전이표엔 없음) — RECOVERY 모드 확인 겸용
        finalization.finalizeChain(o, OrderStatus.FILLED, confirmed, after, ChainNext.markCompleted(),
                CURRENT_PROCESS_CTX, TransitionMode.RECOVERY);

        PortfolioSnapshotEntity snapshot = snapshots.findById("005930").orElseThrow();
        assertThat(snapshot.getArtifactId()).isEqualTo(DECISION_ARTIFACT_ID);
        assertThat(snapshot.getArtifactId()).isNotEqualTo(CURRENT_PROCESS_CTX.artifactId());
    }
}
