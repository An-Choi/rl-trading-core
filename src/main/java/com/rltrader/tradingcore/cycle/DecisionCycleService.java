package com.rltrader.tradingcore.cycle;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.execution.ExecutionPort;
import com.rltrader.tradingcore.execution.FillResult;
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

    private volatile ArtifactContext ctx;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final AtomicBoolean failNextExecutionInsert = new AtomicBoolean(false);   // 테스트 훅
    private final AtomicBoolean corruptNextExecutionFk = new AtomicBoolean(false);    // 테스트 훅 (FK 위반 강제)

    public DecisionCycleService(PredictClient predict, PortfolioManager portfolio,
            ExecutionPort executor, AlertRecorder alerts, DecisionRepository decisions,
            ExecutionRepository executions, PortfolioSnapshotRepository snapshots,
            TransactionTemplate tx, TradingProperties props, ObjectMapper strictMapper,
            java.time.Clock clock) {
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
    }

    static boolean isDuplicateDecision(DataIntegrityViolationException e) {
        return e.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
                && "uq_decisions_symbol_bar".equals(cve.getConstraintName());
    }

    public void initialize(ArtifactContext ctx) {
        this.ctx = ctx;
        this.halted.set(false);
    }

    public boolean isHalted() { return halted.get(); }

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
                halted.set(true);   // 캐시된 env_params가 낡음 — 신규 결정 전면 중단 (spec §2)
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

        FillResult fill = executor.execute(action, state, response.barClose(),
                scheduledBar, ctx);
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
