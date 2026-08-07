package com.rltrader.tradingcore.cycle;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.execution.ExecutionPort;
import com.rltrader.tradingcore.execution.FillResult;
import com.rltrader.tradingcore.halt.TradingHaltState;
import com.rltrader.tradingcore.ledger.*;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.*;
import com.rltrader.tradingcore.risk.RiskGate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Boot 4.1 적응: jackson-databind가 3.x(tools.jackson.databind)라 브리프의
// com.fasterxml.jackson.databind.ObjectMapper / JsonProcessingException 대신
// tools.jackson.databind.ObjectMapper를 사용한다. Jackson 3의 writeValueAsString은
// checked JsonProcessingException이 아니라 unchecked JacksonException을 던진다
// (JacksonException은 이미 RuntimeException 서브타입).
@Service
public class DecisionCycleService {

    private static final Logger log = LoggerFactory.getLogger(DecisionCycleService.class);
    private static final LocalTime WARMUP_END = LocalTime.of(10, 5);

    private final PredictClient predict;
    private final PortfolioManager portfolio;
    private final ExecutionPort executor;
    private final AlertRecorder alerts;
    private final DecisionRepository decisions;
    private final ExecutionRepository executions;
    private final PortfolioSnapshotRepository snapshots;
    private final TransactionTemplate tx;
    private final TradingProperties props;
    private final ObjectMapper mapper;
    private final java.time.Clock clock;
    private final TradingHaltState haltState;
    private final ObjectProvider<LiveCycleOrchestrator> liveOrchestrator;

    private volatile ArtifactContext ctx;
    private final AtomicBoolean failNextExecutionInsert = new AtomicBoolean(false);   // 테스트 훅
    private final AtomicBoolean corruptNextExecutionFk = new AtomicBoolean(false);    // 테스트 훅 (FK 위반 강제)

    public DecisionCycleService(PredictClient predict, PortfolioManager portfolio,
            ExecutionPort executor, AlertRecorder alerts, DecisionRepository decisions,
            ExecutionRepository executions, PortfolioSnapshotRepository snapshots,
            TransactionTemplate tx, TradingProperties props, ObjectMapper strictMapper,
            java.time.Clock clock, TradingHaltState haltState,
            ObjectProvider<LiveCycleOrchestrator> liveOrchestrator) {
        this.predict = predict;
        this.portfolio = portfolio;
        this.executor = executor;
        this.alerts = alerts;
        this.decisions = decisions;
        this.executions = executions;
        this.snapshots = snapshots;
        this.tx = tx;
        this.props = props;
        this.mapper = strictMapper;
        this.clock = clock;
        this.haltState = haltState;
        this.liveOrchestrator = liveOrchestrator;
    }

    static boolean isDuplicateDecision(DataIntegrityViolationException e) {
        return e.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
                && "uq_decisions_symbol_bar".equals(cve.getConstraintName());
    }

    public void initialize(ArtifactContext ctx) {
        this.ctx = ctx;
        // 인메모리 halted를 TradingHaltState로 이관(Task 12) — 이 컴포넌트는 재시작-해제형
        // (halts 테이블 미기록)이 아니라 프로세스 수명 동안 지속되는 상태이므로, initialize()가
        // 매번 halted를 리셋하지는 않는다(과거 AtomicBoolean과 동일한 계약 유지 — 테스트가
        // reset()에서 매번 새 cycle을 만들거나 halted를 false로 되돌릴 필요가 없도록).
    }

    public boolean isHalted() { return haltState.isHalted(); }

    public void failNextExecutionInsertForTest() { failNextExecutionInsert.set(true); }

    public void corruptNextExecutionFkForTest() { corruptNextExecutionFk.set(true); }

    public CycleOutcome runCycle(ZonedDateTime scheduledBar, String asOfOrNull) {
        PortfolioState state = portfolio.current();
        PredictResult result;
        try {
            result = predict.predict(new PredictRequest(props.symbol(),
                    new PredictRequest.Portfolio(state.units(), state.shares(),
                            state.barsSinceEntry(scheduledBar), state.cash()),
                    asOfOrNull));
        } catch (PredictFailureException e) {
            return handlePredictFailure(e, scheduledBar);
        }

        PredictResponse response = result.body();
        Optional<ResponseValidator.Violation> violation =
                ResponseValidator.validate(response, scheduledBar, ctx);
        if (violation.isPresent()) {
            var v = violation.get();
            alerts.record(v.alertCode(), scheduledBar, Map.of("reason", v.reason()));
            if (v.alertCode().equals("ARTIFACT_CHANGED")) {
                // 캐시된 env_params가 낡음 — 신규 결정 전면 중단 (spec §2). TradingHaltState로
                // 이관(Task 12) — halts 테이블에는 미기록이라 재시작하면 풀리는 재시작-해제형
                // 의미는 그대로다(HaltService.raise의 영속 HALT와는 별개, 콜백 배선 없음).
                haltState.halt();
                return CycleOutcome.HALTED;
            }
            return CycleOutcome.NOOP_ALERTED;
        }

        TradeAction action = TradeAction.fromIndex(response.action()).orElseThrow();
        Optional<String> riskViolation =
                RiskGate.check(action, response.actionMask(), state, ctx, props);
        if (riskViolation.isPresent()) {
            alerts.record("MASK_VIOLATION", scheduledBar, Map.of("reason", riskViolation.get()));
            return CycleOutcome.NOOP_ALERTED;
        }

        // live 위임 (Task 12): hold는 주문이 없으므로 1차 단일 tx 경로로 그대로 흘려보낸다
        // (브리프 계약) — LiveCycleOrchestrator는 ADD_UNIT/CLEAR(주문이 실제로 생기는 액션)만
        // 받는다. 검증·RiskGate를 통과한 뒤에만 위임한다.
        LiveCycleOrchestrator live = liveOrchestrator.getIfAvailable();
        if (live != null && action != TradeAction.HOLD) {
            return live.executeCycle(response, action, state, scheduledBar, result.latencyMs(), ctx);
        }

        // dry-run 경로(및 live의 hold): decision INSERT는 아래 persistCycle(같은 tx)에서 일어나므로 이 시점엔
        // 아직 decisionId가 없다 — DryRunExecutor는 이를 무시하므로 플레이스홀더(-1)를 넘긴다
        // (live 경로는 LiveCycleOrchestrator가 tx1 커밋 후 실제 decisionId로 호출, Task 12).
        FillResult fill = executor.execute(action, state, response.barClose(),
                scheduledBar, ctx, -1L);
        try {
            tx.executeWithoutResult(status -> persistCycle(response, action, fill,
                    result.latencyMs()));
        } catch (DataIntegrityViolationException integrity) {
            // DataIntegrityViolationException은 UNIQUE 외에 FK/NOT NULL/길이 위반도 포함 —
            // uq_decisions_symbol_bar일 때만 duplicate, 나머지는 DB 장애로 alert (fail-closed)
            if (isDuplicateDecision(integrity)) {
                log.info("bar {} already processed — noop", scheduledBar);
                return CycleOutcome.NOOP_DUPLICATE;
            }
            alerts.record("DB_ERROR", scheduledBar, Map.of("reason", integrity.toString()));
            return CycleOutcome.NOOP_ALERTED;
        } catch (RuntimeException dbFailure) {
            alerts.record("DB_ERROR", scheduledBar, Map.of("reason", dbFailure.toString()));
            return CycleOutcome.NOOP_ALERTED;
        }
        portfolio.apply(fill.newState());   // 커밋 성공 후에만 메모리 반영 (spec §2)
        return CycleOutcome.EXECUTED;
    }

    private void persistCycle(PredictResponse response, TradeAction action,
            FillResult fill, long latencyMs) {
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
        decisions.save(d);

        if (failNextExecutionInsert.compareAndSet(true, false)) {
            throw new IllegalStateException("test-forced execution insert failure");
        }
        ExecutionEntity e = new ExecutionEntity();
        e.setDecisionId(corruptNextExecutionFk.compareAndSet(true, false)
                ? -999_999L : d.getId());   // 테스트 훅: 존재하지 않는 FK → 비-UNIQUE DIVE 경로
        e.setFillPrice(fill.fillPrice());
        e.setSharesDelta(fill.sharesDelta());
        e.setUnitsAfter(fill.newState().units());
        e.setSharesAfter(fill.newState().shares());
        e.setCashAfter(fill.newState().cash());
        executions.save(e);

        PortfolioSnapshotEntity s = snapshots.findById(props.symbol())
                .orElseGet(PortfolioSnapshotEntity::new);
        s.setSymbol(props.symbol());
        s.setUnits(fill.newState().units());
        s.setShares(fill.newState().shares());
        s.setCash(fill.newState().cash());
        s.setEntryBarTs(fill.newState().entryBarTs() != null
                ? fill.newState().entryBarTs().toOffsetDateTime() : null);
        s.setArtifactId(ctx.artifactId());
        s.setUpdatedAt(OffsetDateTime.now(clock));   // 모든 시각은 주입된 Clock 경유
        snapshots.save(s);
    }

    private CycleOutcome handlePredictFailure(PredictFailureException e,
            ZonedDateTime scheduledBar) {
        boolean warmup = scheduledBar.toLocalTime().isBefore(WARMUP_END);
        if (warmup && "STALE_DATA".equals(e.code())) {
            log.info("warmup stale (expected) at {}", scheduledBar);   // spec §6: 무알림
            return CycleOutcome.NOOP_EXPECTED_STALE;
        }
        alerts.record("PREDICT_" + e.code(), scheduledBar,
                Map.of("httpStatus", e.httpStatus(), "message", String.valueOf(e.getMessage())));
        return CycleOutcome.NOOP_ALERTED;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
