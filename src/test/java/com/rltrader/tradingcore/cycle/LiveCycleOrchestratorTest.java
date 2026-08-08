package com.rltrader.tradingcore.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rltrader.tradingcore.config.JacksonConfig;
import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.execution.ExecutionPort;
import com.rltrader.tradingcore.execution.FillResult;
import com.rltrader.tradingcore.execution.OrderPlan;
import com.rltrader.tradingcore.execution.OrderPlanner;
import com.rltrader.tradingcore.ledger.AlertRecorder;
import com.rltrader.tradingcore.ledger.DecisionEntity;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.predict.PredictResponse;
import com.rltrader.tradingcore.reconcile.ReconciliationService;
import com.rltrader.tradingcore.reconcile.Trigger;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 13 리뷰 수정 — LiveCycleOrchestrator.triggerPostOrderReconcile의 실패 격리를 순수 Mockito
 * 단위 테스트로 검증한다(Spring 컨텍스트 없이 협력자 전부 mock — TransactionTemplate은 인터페이스가
 * 아니지만 final이 아니므로 execute()의 콜백을 즉시 호출하도록 스텁한다).
 * reconcile(POST_ORDER)가 RuntimeException을 던져도 이미 커밋된 체인의 CycleOutcome.EXECUTED는
 * 뒤집히지 않고, POST_ORDER_RECONCILE_FAILED 알림만 남아야 한다(ChainIncompleteException 캐치에
 * 흡수돼 NOOP_ALERTED로 떨어지면 안 된다 — 이미 성공한 거래를 실패로 오분류하는 버그).
 */
class LiveCycleOrchestratorTest {

    private DecisionRepository decisions;
    private OrderRepository orders;
    private OrderPlanner planner;
    private ExecutionPort executor;
    private AlertRecorder alerts;
    private TransactionTemplate tx;
    private TradingProperties props;
    private ReconciliationService reconciliationService;
    private ObjectProvider<ReconciliationService> reconciliationProvider;
    private LiveCycleOrchestrator orchestrator;

    private static final ArtifactContext CTX = new ArtifactContext("ppo-fs3-x", 3, 0.199, 10_000.0, 5);
    private static final ZonedDateTime BAR =
            ZonedDateTime.parse("2026-08-07T11:00:00+09:00[Asia/Seoul]");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        decisions = mock(DecisionRepository.class);
        orders = mock(OrderRepository.class);
        planner = mock(OrderPlanner.class);
        executor = mock(ExecutionPort.class);
        alerts = mock(AlertRecorder.class);
        tx = mock(TransactionTemplate.class);
        props = new TradingProperties("005930", "http://predict", 0, 5, 0.199,
                10_000.0, 1000, 1000, 1000, 5);
        reconciliationService = mock(ReconciliationService.class);
        reconciliationProvider = mock(ObjectProvider.class);
        when(reconciliationProvider.getIfAvailable()).thenReturn(reconciliationService);

        // tx.execute(callback) — 실제 트랜잭션 없이 콜백을 즉시 실행하고 decisionId(1L)를 반환.
        when(tx.execute(Mockito.<org.springframework.transaction.support.TransactionCallback<Long>>any()))
                .thenAnswer(inv -> {
                    org.springframework.transaction.support.TransactionCallback<Long> cb = inv.getArgument(0);
                    return cb.doInTransaction(null);
                });

        when(planner.plan(any(), any(), any(), Mockito.anyDouble()))
                .thenReturn(Optional.of(new OrderPlan(OrderSide.BUY, 19L)));
        // 실제 JPA save는 신규 엔티티에 생성된 id를 채운다 — mock repository는 그렇지 않으므로
        // commitTx1의 d.getId()(long 언박싱)가 NPE로 터지지 않게 직접 채워준다.
        Mockito.doAnswer(inv -> {
            DecisionEntity d = inv.getArgument(0);
            d.setId(1L);
            return d;
        }).when(decisions).save(Mockito.any(DecisionEntity.class));

        orchestrator = new LiveCycleOrchestrator(decisions, orders, planner, executor, alerts,
                tx, props, JacksonConfig.strictMapper(), reconciliationProvider);
    }

    private PredictResponse predictResponse() {
        return new PredictResponse(1, "add_unit", List.of(true, true, true),
                OffsetDateTime.parse("2026-08-07T02:00:00Z"), CTX.artifactId(), 3,
                List.of(0.1, 0.2), 100.0);
    }

    @Test
    void post_order_reconcile_failure_does_not_flip_executed_outcome() {
        when(executor.execute(any(), any(), Mockito.anyDouble(), any(), any(), anyLong()))
                .thenReturn(new FillResult(PortfolioState.flat(10_000.0), 100.0, 19.0, true));
        doThrow(new IllegalStateException("boom — adjustment lock timeout"))
                .when(reconciliationService).reconcile(eq(Trigger.POST_ORDER));

        CycleOutcome outcome = orchestrator.executeCycle(predictResponse(), TradeAction.ADD_UNIT,
                PortfolioState.flat(10_000.0), BAR, 5L, CTX);

        assertThat(outcome).isEqualTo(CycleOutcome.EXECUTED);
        verify(reconciliationService).reconcile(Trigger.POST_ORDER);
        verify(alerts).record(eq("POST_ORDER_RECONCILE_FAILED"), eq(BAR), Mockito.anyMap());
    }

    @Test
    void post_order_reconcile_runs_and_succeeds_without_alert() {
        when(executor.execute(any(), any(), Mockito.anyDouble(), any(), any(), anyLong()))
                .thenReturn(new FillResult(PortfolioState.flat(10_000.0), 100.0, 19.0, true));

        CycleOutcome outcome = orchestrator.executeCycle(predictResponse(), TradeAction.ADD_UNIT,
                PortfolioState.flat(10_000.0), BAR, 5L, CTX);

        assertThat(outcome).isEqualTo(CycleOutcome.EXECUTED);
        verify(reconciliationService).reconcile(Trigger.POST_ORDER);
        verify(alerts, Mockito.never()).record(eq("POST_ORDER_RECONCILE_FAILED"), any(), Mockito.anyMap());
    }

    @Test
    void no_reconcile_call_when_hadTimeout_false() {
        when(executor.execute(any(), any(), Mockito.anyDouble(), any(), any(), anyLong()))
                .thenReturn(new FillResult(PortfolioState.flat(10_000.0), 100.0, 19.0, false));

        CycleOutcome outcome = orchestrator.executeCycle(predictResponse(), TradeAction.ADD_UNIT,
                PortfolioState.flat(10_000.0), BAR, 5L, CTX);

        assertThat(outcome).isEqualTo(CycleOutcome.EXECUTED);
        verify(reconciliationService, Mockito.never()).reconcile(any());
    }
}
