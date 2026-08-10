package com.rltrader.tradingcore.scheduler;

import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.execution.LiquidationService;
import com.rltrader.tradingcore.halt.HaltService;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisTokenManager;
import com.rltrader.tradingcore.kis.KisTransportException;
import com.rltrader.tradingcore.ledger.DecisionEntity;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.portfolio.PortfolioManager.RawRestoreResult;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.reconcile.ReconciliationService;
import com.rltrader.tradingcore.reconcile.Trigger;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * live 기동 순서(Task 14, spec §7) ①~⑨ 소유. {@code @Profile("live")} 전용 — dry-run
 * 프로파일에는 이 빈 자체가 없으므로 {@link BootSequence}의 {@code ObjectProvider}가 항상 null을
 * 반환한다(1차 경로 무변경, 브리프 계약). ⑩(scheduler enable)은 여전히 {@link StartupRunner}의
 * 몫 — 이 클래스는 그 앞의 모든 게이트만 담당한다.
 *
 * <p>기동 순서를 이 클래스 하나의 메서드 호출 순서로 강제한다 — {@link BootSequence#prepare()}는
 * ①·②·③(핸드셰이크는 여전히 BootSequence 소유, HandshakeValidator가 순수 함수라 분리 이점이
 * 없다)만 직접 부르고 ④~⑨는 {@link #recoverAndVerify(ArtifactContext)} 안에서 한 메서드가
 * 하드코딩된 순서로 호출한다 — 순서가 코드 구조 자체이므로 호출부가 실수로 뒤섞을 수 없다.
 */
@Component
@Profile("live")
public class BootRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(BootRecoveryService.class);
    private static final Set<OrderStatus> NON_TERMINAL =
            EnumSet.complementOf(EnumSet.of(
                    OrderStatus.FILLED,
                    OrderStatus.CANCELLED,
                    OrderStatus.REJECTED,
                    OrderStatus.ABORTED));

    private final HaltService halts;
    private final KisTokenManager tokenManager;
    private final PortfolioManager portfolio;
    private final OrderRepository orders;
    private final DecisionRepository decisions;
    private final ReconciliationService reconciliation;
    private final LiquidationService liquidation;
    private final BrokerPort broker;
    private final Clock clock;

    public BootRecoveryService(HaltService halts, KisTokenManager tokenManager,
            PortfolioManager portfolio, OrderRepository orders, DecisionRepository decisions,
            ReconciliationService reconciliation, LiquidationService liquidation,
            BrokerPort broker, Clock clock) {
        this.halts = halts;
        this.tokenManager = tokenManager;
        this.portfolio = portfolio;
        this.orders = orders;
        this.decisions = decisions;
        this.reconciliation = reconciliation;
        this.liquidation = liquidation;
        this.broker = broker;
        this.clock = clock;
    }

    /** ① 실패 = 기동 중단(IllegalStateException 그대로 전파). */
    public void assertNoActiveHalt() {
        halts.assertNoActiveHalt();
    }

    /** ② 토큰 발급 확인 — 실패 시 KisTransportException이 그대로 전파되어 기동 중단. */
    public void ensureToken() {
        tokenManager.accessToken();
    }

    /**
     * ④~⑨ — 원본 복원(④) → open-order 복구(⑤) → 체인 불변식(⑥) → artifact 판정(⑦) →
     * liquidation 재개 확인(⑧) → 최종 대사(⑨). 어느 한 단계라도 HALT를 내면 그 지점에서
     * RuntimeException을 던져 이후 단계로 진행하지 않는다(브리프 "위반 → HALT + 기동 중단").
     */
    public void recoverAndVerify(ArtifactContext ctx) {
        RawRestoreResult raw = portfolio.restoreRaw(ctx);   // ④

        reconciliation.reconcileOrdersOnly(Trigger.STARTUP);   // ⑤ open orders 복구(관찰 점프
        // 확정, 발견/미발견 해소 — ReconciliationService.reconcileOrders의 STARTUP 전용
        // 오차니 발견 단계가 흡수한다, 중복 구현 없음). 미발견·모호면 이 호출 안에서
        // halts.raise + IllegalStateException으로 이미 기동이 중단된다. 잔고 레벨은 아직
        // 부르지 않는다 — ④에서 캡처한 artifact mismatch가 아직 판정(⑦)되지 않은 채로 로컬
        // shares를 KIS와 비교하면 미해결 mismatch를 RECON_UNEXPLAINED로 오판할 수 있다.

        assertChainInvariant();   // ⑥

        verifyArtifact(ctx, raw);   // ⑦ — mismatch 아니면 즉시 반환, mismatch면 실보유 확인 후
        // HALT 또는 flat 초기화.

        liquidation.pendingActive();   // ⑧ — 확인만(스케줄러가 실제 재개를 수행, 브리프 계약).

        reconciliation.reconcile(Trigger.STARTUP);   // ⑨ 최종 대사(order+balance 둘 다) — ⑤~⑦이
        // 상태를 확정지은 뒤에야 잔고 레벨 비교가 의미 있다. order-level은 이미 클린(재확인),
        // balance-level이 이 호출에서 처음 실행된다.
    }

    /**
     * ⑥ 체인 불변식 — IN_PROGRESS decision마다 (그 decision에 속한 비종결 주문이 있음) ∨
     * (그 심볼의 snapshot이 liquidation_pending) 중 하나는 참이어야 한다. 둘 다 거짓이면
     * "진행 중이라고 기록된 결정인데 아무 것도 그 진행을 설명하지 못하는" 상태 — RECON_UNEXPLAINED로
     * 즉시 HALT + 기동 중단.
     */
    private void assertChainInvariant() {
        List<DecisionEntity> inProgress = decisions.findByExecutionStatus("IN_PROGRESS");
        if (inProgress.isEmpty()) {
            return;
        }
        List<OrderEntity> nonTerminal = orders.findByStatusIn(NON_TERMINAL);
        boolean liquidationPending = liquidation.pendingActive();
        for (DecisionEntity d : inProgress) {
            boolean hasNonTerminalOrder = nonTerminal.stream().anyMatch(o -> o.getDecisionId() == d.getId());
            if (!hasNonTerminalOrder && !liquidationPending) {
                halts.raise("RECON_UNEXPLAINED", Map.of(
                        "decisionId", d.getId(), "symbol", d.getSymbol(),
                        "reason", "IN_PROGRESS decision with no non-terminal order and no "
                                + "liquidation-pending snapshot — chain invariant violated at boot"));
                throw new IllegalStateException(
                        "boot chain invariant violated for decision " + d.getId());
            }
        }
    }

    /**
     * ⑦ artifact 판정 — ④에서 캡처한 mismatch만 근거로 삼는다(복구 도중 snapshot이 다시
     * 쓰였을 수 있으므로 이 시점에 재비교하지 않는다). mismatch가 아니면 아무 것도 하지 않는다
     * (조용히 통과 — 정상 케이스).
     */
    private void verifyArtifact(ArtifactContext ctx, RawRestoreResult raw) {
        if (!raw.artifactMismatch()) {
            return;
        }
        KisBalance balance;
        try {
            balance = broker.balance();
        } catch (KisTransportException e) {
            throw new IllegalStateException("artifact mismatch verdict requires KIS balance — fetch failed", e);
        }
        if (balance.holdingQty() > 0) {
            halts.raise("ARTIFACT_POSITION_MISMATCH", Map.of(
                    "priorArtifactId", String.valueOf(raw.priorArtifactId()),
                    "currentArtifactId", ctx.artifactId(), "kisHoldingQty", balance.holdingQty()));
            throw new IllegalStateException(
                    "artifact mismatch with real holdings (" + balance.holdingQty() + ") — boot halted");
        }
        log.warn("artifact mismatch ({} != {}) but KIS holding is 0 — resetting to flat",
                raw.priorArtifactId(), ctx.artifactId());
        portfolio.resetFlatAfterArtifactMismatch(ctx);
    }
}
