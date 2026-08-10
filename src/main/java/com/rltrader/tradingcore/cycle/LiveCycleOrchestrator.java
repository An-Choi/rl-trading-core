package com.rltrader.tradingcore.cycle;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.domain.TradingGrid;
import com.rltrader.tradingcore.execution.ChainIncompleteException;
import com.rltrader.tradingcore.execution.ExecutionPort;
import com.rltrader.tradingcore.execution.FillResult;
import com.rltrader.tradingcore.execution.OrderPlan;
import com.rltrader.tradingcore.execution.OrderPlanner;
import com.rltrader.tradingcore.ledger.AlertRecorder;
import com.rltrader.tradingcore.ledger.DecisionEntity;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.predict.PredictResponse;
import com.rltrader.tradingcore.reconcile.ReconciliationService;
import com.rltrader.tradingcore.reconcile.Trigger;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * live 사이클 오케스트레이션 (Task 12) — tx1 소유: decisions INSERT(execution_status=
 * IN_PROGRESS) + (주문이 있으면) orders NEW INSERT를 한 tx로 커밋한 뒤, 커밋 후에만 executor
 * (KisOrderExecutor)를 호출한다 — "기록 먼저, 제출 나중"(spec §2). 메모리(PortfolioManager) 갱신은
 * 이 클래스가 아니라 OrderFinalizationService가 각 finalization 커밋 직후 수행한다(Task 8 규칙) —
 * 이 클래스는 그 결과를 CycleOutcome으로 매핑만 한다.
 *
 * <p>hold(주문 없음)는 이 오케스트레이터에 위임되지 않는다 — DecisionCycleService가 1차 단일 tx
 * 경로(persistCycle)로 직접 처리한다(브리프 계약).
 */
@Component
@Profile("live")
public class LiveCycleOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LiveCycleOrchestrator.class);

    private final DecisionRepository decisions;
    private final OrderRepository orders;
    private final OrderPlanner planner;
    private final ExecutionPort executor;
    private final AlertRecorder alerts;
    private final TransactionTemplate tx;
    private final TradingProperties props;
    private final ObjectMapper mapper;
    private final ObjectProvider<ReconciliationService> reconciliation;

    public LiveCycleOrchestrator(DecisionRepository decisions, OrderRepository orders,
            OrderPlanner planner, ExecutionPort executor, AlertRecorder alerts,
            TransactionTemplate tx, TradingProperties props, ObjectMapper strictMapper,
            ObjectProvider<ReconciliationService> reconciliation) {
        this.decisions = decisions;
        this.orders = orders;
        this.planner = planner;
        this.executor = executor;
        this.alerts = alerts;
        this.tx = tx;
        this.props = props;
        this.mapper = strictMapper;
        this.reconciliation = reconciliation;
    }

    public CycleOutcome executeCycle(PredictResponse response, TradeAction action,
            PortfolioState state, java.time.ZonedDateTime barTs, long latencyMs, ArtifactContext ctx) {
        Optional<OrderPlan> plan = planner.plan(action, state, ctx, response.barClose());

        Long decisionId;
        try {
            decisionId = tx.execute(status -> commitTx1(response, latencyMs, ctx, plan, barTs));
        } catch (DataIntegrityViolationException integrity) {
            if (DecisionCycleService.isDuplicateDecision(integrity)) {
                log.info("bar {} already processed — noop", barTs);
                return CycleOutcome.NOOP_DUPLICATE;
            }
            alerts.record("DB_ERROR", barTs, Map.of("reason", integrity.toString()));
            return CycleOutcome.NOOP_ALERTED;
        } catch (RuntimeException dbFailure) {
            alerts.record("DB_ERROR", barTs, Map.of("reason", dbFailure.toString()));
            return CycleOutcome.NOOP_ALERTED;
        }

        try {
            // FillResult.newState는 사용하지 않는다 — 실제 메모리 반영은 OrderFinalizationService가
            // 커밋 직후 이미 수행했다(spec §2, Task 8 규칙). 여기서 중복 반영하지 않는다.
            // hadTimeout만은 예외 — Task 13: 이 체인이 fill-timeout(취소 경로)을 한 번이라도
            // 거쳤다면 POST_ORDER 강제 대사를 트리거한다(lifecycle→recon 직접 의존 금지, 이
            // orchestrator가 ChainOutcome.hadTimeout을 FillResult 경계 너머로 보고 호출한다).
            FillResult result = executor.execute(action, state, response.barClose(), barTs, ctx, decisionId);
            if (result.hadTimeout()) {
                triggerPostOrderReconcile(barTs, decisionId);
            }
            return CycleOutcome.EXECUTED;
        } catch (ChainIncompleteException incomplete) {
            alerts.record("CHAIN_INCOMPLETE", barTs, Map.of(
                    "decisionId", decisionId, "reason", String.valueOf(incomplete.getMessage())));
            return CycleOutcome.NOOP_ALERTED;
        }
    }

    /**
     * 리뷰 수정 — 이 시점에 executor.execute()는 이미 성공 커밋을 마쳤다(주문 체인 자체는 완료).
     * reconcile(POST_ORDER)이 던지는 어떤 예외(예: adjustment tx 내부의 orElseThrow)도 executeCycle의
     * ChainIncompleteException 캐치에 흡수되지 않고 그대로 새면, 이미 성공한 사이클이
     * CycleOutcome 없이 스케줄러 tick을 그대로 실패시킨다 — 대사 실패가 이미 끝난 거래의 성공
     * 여부를 뒤집으면 안 되므로 별도로 격리해 알림만 남기고 EXECUTED를 유지한다.
     */
    private void triggerPostOrderReconcile(java.time.ZonedDateTime barTs, long decisionId) {
        ReconciliationService recon = reconciliation.getIfAvailable();
        if (recon == null) {
            return;
        }
        try {
            recon.reconcile(Trigger.POST_ORDER);
        } catch (RuntimeException reconFailure) {
            alerts.record("POST_ORDER_RECONCILE_FAILED", barTs, Map.of(
                    "decisionId", decisionId, "reason", String.valueOf(reconFailure.getMessage())));
        }
    }

    /** tx1 — decisions INSERT(IN_PROGRESS) + (주문 있으면) orders NEW INSERT, 같은 tx. */
    private long commitTx1(PredictResponse response, long latencyMs,
            ArtifactContext ctx, Optional<OrderPlan> plan, java.time.ZonedDateTime barTs) {
        DecisionEntity d = new DecisionEntity();
        d.setSymbol(props.symbol());
        d.setBarTs(response.barTs());
        d.setAction(response.action());
        d.setLabel(response.label());
        d.setActionMask(json(response.actionMask()));
        d.setObservation(json(response.observation()));
        d.setBarClose(response.barClose());
        d.setArtifactId(ctx.artifactId());
        d.setLatencyMs(latencyMs);
        d.setExecutionStatus("IN_PROGRESS");
        decisions.save(d);

        if (plan.isPresent()) {
            OrderEntity o = new OrderEntity();
            o.setDecisionId(d.getId());
            o.setAttempt(1);
            o.setTradingDate(barTs.withZoneSameInstant(TradingGrid.KST).toLocalDate());
            o.setSymbol(props.symbol());
            o.setSide(plan.get().side());
            o.setQty(plan.get().qty());
            o.setOrderType("MARKET");
            o.setStatus(OrderStatus.NEW);
            o.setAccountedFillNotional(BigDecimal.ZERO);
            orders.save(o);
        }
        return d.getId();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
