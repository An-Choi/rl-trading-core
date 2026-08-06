package com.rltrader.tradingcore.cycle;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.ledger.*;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class DecisionCycleIntegrationTest extends AbstractIntegrationTest {

    static WireMockServer wiremock = new WireMockServer(WireMockConfiguration.options().port(18099));

    @DynamicPropertySource
    static void predictUrl(DynamicPropertyRegistry registry) {
        registry.add("trading.predict-base-url", () -> "http://127.0.0.1:18099");
        registry.add("trading.read-timeout-ms", () -> 2000);
    }

    @BeforeAll static void startWm() { wiremock.start(); }
    @AfterAll static void stopWm() { wiremock.stop(); }

    @Autowired DecisionCycleService cycle;
    @Autowired PortfolioManager portfolio;
    @Autowired DecisionRepository decisions;
    @Autowired ExecutionRepository executions;
    @Autowired AlertRepository alerts;
    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired AlertRecorder alertRecorder;

    static final ZonedDateTime BAR =
            ZonedDateTime.parse("2026-08-06T11:00:00+09:00[Asia/Seoul]");
    static final ArtifactContext CTX = new ArtifactContext("ppo-fs3-x", 3, 0.199, 10_000.0, 5);

    private static String okBody(int action, String label, String barTs) {
        return """
            {"action": %d, "label": "%s", "action_mask": [true, true, true],
             "bar_ts": "%s", "artifact_id": "ppo-fs3-x", "feature_schema_version": 3,
             "observation": [0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,0.0,0.0,0.0,0.5],
             "bar_close": 71000.0}""".formatted(action, label, barTs);
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
        executions.deleteAll();
        decisions.deleteAll();
        alerts.deleteAll();
        snapshots.deleteAll();
        portfolio.apply(PortfolioState.flat(10_000.0));
        cycle.initialize(CTX);
    }

    @Test
    void addUnitExecutesAtomically() {
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-06T11:00:00+09:00"))));
        CycleOutcome outcome = cycle.runCycle(BAR, null);
        assertThat(outcome).isEqualTo(CycleOutcome.EXECUTED);
        assertThat(portfolio.current().units()).isEqualTo(1);
        assertThat(portfolio.current().entryBarTs()).isNotNull();
        assertThat(decisions.count()).isEqualTo(1);
        assertThat(executions.count()).isEqualTo(1);
        assertThat(snapshots.findById("005930")).isPresent();
    }

    @Test
    void holdLeavesStateFullyUnchangedButRecordsDecisionAndExecution() {
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(0, "hold", "2026-08-06T11:00:00+09:00"))));
        CycleOutcome outcome = cycle.runCycle(BAR, null);
        assertThat(outcome).isEqualTo(CycleOutcome.EXECUTED);
        assertThat(portfolio.current().units()).isZero();
        assertThat(portfolio.current().shares()).isZero();
        assertThat(portfolio.current().cash()).isEqualTo(10_000.0);
        assertThat(portfolio.current().entryBarTs()).isNull();
        assertThat(decisions.count()).isEqualTo(1);
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void addThenClearRestoresFlatStateAndClearsEntryBarTs() {
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-06T11:00:00+09:00"))));
        CycleOutcome added = cycle.runCycle(BAR, null);
        assertThat(added).isEqualTo(CycleOutcome.EXECUTED);
        assertThat(portfolio.current().units()).isEqualTo(1);
        assertThat(portfolio.current().entryBarTs()).isNotNull();
        assertThat(snapshots.findById("005930").orElseThrow().getEntryBarTs()).isNotNull();

        ZonedDateTime clearBar = BAR.plusMinutes(5);
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(2, "clear", "2026-08-06T11:05:00+09:00"))));
        CycleOutcome cleared = cycle.runCycle(clearBar, null);

        assertThat(cleared).isEqualTo(CycleOutcome.EXECUTED);
        assertThat(portfolio.current().units()).isZero();
        assertThat(portfolio.current().shares()).isZero();
        // add_unit 지출(1990) 후 같은 bar_close(71000.0)로 청산 — cash가 초기값으로 정확히 복원
        assertThat(portfolio.current().cash()).isEqualTo(10_000.0);
        assertThat(portfolio.current().entryBarTs()).isNull();
        assertThat(decisions.count()).isEqualTo(2);
        assertThat(executions.count()).isEqualTo(2);
        assertThat(snapshots.findById("005930").orElseThrow().getEntryBarTs()).isNull();
    }

    @Test
    void duplicateBarIsNoop() {
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-06T11:00:00+09:00"))));
        cycle.runCycle(BAR, null);
        CycleOutcome second = cycle.runCycle(BAR, null);
        assertThat(second).isEqualTo(CycleOutcome.NOOP_DUPLICATE);
        assertThat(portfolio.current().units()).isEqualTo(1);   // 상태 1회만 진행
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void wrongBarIsRejectedWithoutDecisionRow() {
        // staleness 허용창의 직전 bar 응답 — 검증 ③
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-06T10:55:00+09:00"))));
        CycleOutcome outcome = cycle.runCycle(BAR, null);
        assertThat(outcome).isEqualTo(CycleOutcome.NOOP_ALERTED);
        assertThat(decisions.count()).isZero();                 // INSERT 자체를 안 함
        assertThat(portfolio.current().units()).isZero();
        assertThat(alerts.findAll()).anyMatch(a -> a.getCode().equals("WRONG_BAR"));
    }

    @Test
    void artifactChangeHaltsScheduler() {
        String body = okBody(0, "hold", "2026-08-06T11:00:00+09:00")
                .replace("ppo-fs3-x", "ppo-fs3-NEW");
        wiremock.stubFor(post("/predict").willReturn(okJson(body)));
        CycleOutcome outcome = cycle.runCycle(BAR, null);
        assertThat(outcome).isEqualTo(CycleOutcome.HALTED);
        assertThat(cycle.isHalted()).isTrue();
        assertThat(alerts.findAll()).anyMatch(a -> a.getCode().equals("ARTIFACT_CHANGED"));
    }

    @Test
    void warmupStaleIsInfoOnly() {
        wiremock.stubFor(post("/predict").willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"code\": \"STALE_DATA\", \"message\": \"m\", \"detail\": {}}}")));
        ZonedDateTime warmupBar = ZonedDateTime.parse("2026-08-06T09:30:00+09:00[Asia/Seoul]");
        CycleOutcome outcome = cycle.runCycle(warmupBar, null);
        assertThat(outcome).isEqualTo(CycleOutcome.NOOP_EXPECTED_STALE);
        assertThat(alerts.count()).isZero();                    // 무알림
        // 같은 STALE_DATA라도 10:05 이후는 alert
        CycleOutcome after = cycle.runCycle(BAR, null);
        assertThat(after).isEqualTo(CycleOutcome.NOOP_ALERTED);
        assertThat(alerts.findAll()).anyMatch(a -> a.getCode().equals("PREDICT_STALE_DATA"));
    }

    @Test
    void invalidResponsesAreRejected() {
        String nanClose = okBody(0, "hold", "2026-08-06T11:00:00+09:00")
                .replace("71000.0", "NaN");
        wiremock.stubFor(post("/predict").willReturn(okJson(nanClose)));
        assertThat(cycle.runCycle(BAR, null)).isEqualTo(CycleOutcome.NOOP_ALERTED);

        String shortMask = okBody(0, "hold", "2026-08-06T11:00:00+09:00")
                .replace("[true, true, true]", "[true, true]");
        wiremock.stubFor(post("/predict").willReturn(okJson(shortMask)));
        assertThat(cycle.runCycle(BAR.plusMinutes(5), null)).isEqualTo(CycleOutcome.NOOP_ALERTED);

        String wrongLabel = okBody(1, "clear", "2026-08-06T11:10:00+09:00");
        wiremock.stubFor(post("/predict").willReturn(okJson(wrongLabel)));
        assertThat(cycle.runCycle(BAR.plusMinutes(10), null)).isEqualTo(CycleOutcome.NOOP_ALERTED);

        String shortObs = okBody(0, "hold", "2026-08-06T11:15:00+09:00")
                .replace(",0.5]", "]");
        wiremock.stubFor(post("/predict").willReturn(okJson(shortObs)));
        assertThat(cycle.runCycle(BAR.plusMinutes(15), null)).isEqualTo(CycleOutcome.NOOP_ALERTED);

        assertThat(portfolio.current().units()).isZero();       // 전부 상태 무변경
        assertThat(decisions.count()).isZero();
    }

    @Test
    void maskViolationIsRejected() {
        String maskedAdd = okBody(1, "add_unit", "2026-08-06T11:00:00+09:00")
                .replace("[true, true, true]", "[true, false, true]");
        wiremock.stubFor(post("/predict").willReturn(okJson(maskedAdd)));
        assertThat(cycle.runCycle(BAR, null)).isEqualTo(CycleOutcome.NOOP_ALERTED);
        assertThat(alerts.findAll()).anyMatch(a -> a.getCode().equals("MASK_VIOLATION"));
    }

    @Test
    void nonUniqueIntegrityViolationIsDbErrorNotDuplicate() {
        // FK/NOT NULL 위반은 DataIntegrityViolationException이지만 duplicate가 아니다 —
        // constraint 이름 판별이 없으면 NOOP_DUPLICATE로 오분류되어 DB 장애 alert를 잃는다
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-06T11:00:00+09:00"))));
        cycle.corruptNextExecutionFkForTest();
        CycleOutcome first = cycle.runCycle(BAR, null);
        assertThat(first).isEqualTo(CycleOutcome.NOOP_ALERTED);
        assertThat(alerts.findAll()).anyMatch(a -> a.getCode().equals("DB_ERROR"));
        assertThat(decisions.count()).isZero();                 // 트랜잭션 전체 롤백
        assertThat(portfolio.current().units()).isZero();
        assertThat(cycle.runCycle(BAR, null)).isEqualTo(CycleOutcome.EXECUTED); // 재시도 정상
    }

    @Test
    void transactionRollbackKeepsMemoryUnchanged() {
        // executions 저장 실패 강제 → decisions 롤백 + 메모리 무변경 + 다음 재시도 정상
        wiremock.stubFor(post("/predict").willReturn(okJson(
                okBody(1, "add_unit", "2026-08-06T11:00:00+09:00"))));
        cycle.failNextExecutionInsertForTest();                 // 테스트 훅 (아래 구현 참조)
        CycleOutcome first = cycle.runCycle(BAR, null);
        assertThat(first).isEqualTo(CycleOutcome.NOOP_ALERTED);
        assertThat(decisions.count()).isZero();                 // 원자성: decisions도 롤백
        assertThat(portfolio.current().units()).isZero();
        CycleOutcome retry = cycle.runCycle(BAR, null);
        assertThat(retry).isEqualTo(CycleOutcome.EXECUTED);     // 재시도 성공 (UNIQUE 미오염)
    }

    @Test
    void recordTruncatesOversizedCodeToFitColumn() {
        // alerts.code는 VARCHAR(32) — 초과 시 truncate하지 않으면 best-effort INSERT가
        // 통째로 실패해 계약 위반 상황에서 오히려 행을 잃는다
        String oversized = "A".repeat(35);
        assertThat(oversized.length()).isEqualTo(35);
        alertRecorder.record(oversized, BAR, java.util.Map.of());
        assertThat(alerts.findAll())
                .anyMatch(a -> a.getCode().equals("A".repeat(32)) && a.getCode().length() == 32);
    }
}
