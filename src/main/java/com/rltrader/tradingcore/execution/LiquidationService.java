package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.ledger.DecisionEntity;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 10 — clear(SELL) 체인이 EOD·진행불가로 잔량을 남긴 채 이월(liquidation_pending)된 상태를
 * 다음 세션에서 재개한다. {@link #resume(ZonedDateTime)}는 잔량만큼 새 attempt(NEW)를 단독 tx로
 * 선기록한 뒤(플래그가 이미 영속되어 있어 이 시점 크래시도 안전 — spec §4) 기존
 * {@link OrderLifecycleService#runChain}을 그대로 재사용해 제출부터 다시 진행한다
 * (in-process/restart 동형성, Task 9와 동일 원칙).
 *
 * <p><b>부트 순서 계약(리뷰 수정, Important)</b>: {@link #resume(ZonedDateTime)}은
 * {@link PortfolioManager#current()}를 읽는다 — 즉 호출 전에 {@link PortfolioManager#restore}가
 * 이미 실행되어 있어야 한다(스냅숏에서 메모리 상태를 복원). 부팅 직후 restore 이전에 resume을
 * 호출하면 {@link IllegalStateException}으로 즉시 실패한다(아래 명시적 가드). 스케줄러/부트
 * 시퀀스가 이 순서(restore → resume)를 보장해야 하며, 그 배선은 Task 12/14 범위다.
 */
@Component
public class LiquidationService {

    private final PortfolioSnapshotRepository snapshots;
    private final DecisionRepository decisions;
    private final OrderRepository orders;
    private final OrderLifecycleService lifecycle;
    private final PortfolioManager portfolio;
    private final TransactionTemplate tx;

    public LiquidationService(PortfolioSnapshotRepository snapshots, DecisionRepository decisions,
            OrderRepository orders, OrderLifecycleService lifecycle, PortfolioManager portfolio,
            TransactionTemplate tx) {
        this.snapshots = snapshots;
        this.decisions = decisions;
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.portfolio = portfolio;
        this.tx = tx;
    }

    /** 이월 대기 중인 clear 체인이 있는지 — snapshot.liquidationPending 조회. */
    public boolean pendingActive() {
        return findPendingSnapshot().isPresent();
    }

    /**
     * 재개: pending snapshot에서 심볼 확인 → IN_PROGRESS + label=clear decision 조회 → 잔량은
     * 스냅숏 shares(정수 투영) → 후속 attempt NEW를 단독 tx로 선기록 → runChain 계속(제출부터).
     * 완료 시 runChain의 정상 종료 경로(ChainNext.markCompleted() — completed=true +
     * liquidationPending=false)가 COMPLETED + units=0 + 플래그 해제를 한 tx로 커밋한다.
     *
     * <p><b>사전조건(리뷰 수정, Important)</b>: 호출 전에 {@link PortfolioManager#restore}가 이미
     * 실행되어 있어야 한다 — 이 메서드는 재개 attempt의 {@code before} 상태를
     * {@link PortfolioManager#current()}에서 읽는다. 아래 가드가 위반 시 즉시 실패시킨다(클래스
     * 레벨 계약 참고).
     */
    public ChainOutcome resume(ZonedDateTime now) {
        if (!portfolio.isRestored()) {
            throw new IllegalStateException(
                    "LiquidationService.resume() requires PortfolioManager.restore() to have run first "
                            + "(boot-order contract — see LiquidationService class Javadoc)");
        }
        PortfolioSnapshotEntity snapshot = findPendingSnapshot()
                .orElseThrow(() -> new IllegalStateException("no liquidation-pending snapshot to resume"));
        DecisionEntity decision = findInProgressClearDecision(snapshot.getSymbol())
                .orElseThrow(() -> new IllegalStateException(
                        "no IN_PROGRESS clear decision for pending liquidation: " + snapshot.getSymbol()));
        OrderEntity lastOrder = orders.findTopByDecisionIdOrderByAttemptDesc(decision.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "no prior order for decision " + decision.getId()));

        long remaining = Math.round(snapshot.getShares().doubleValue());
        OrderEntity nextAttempt = lifecycle.newAttempt(lastOrder, remaining);
        tx.executeWithoutResult(s -> orders.save(nextAttempt));   // 단독 tx 선기록 — 크래시 안전

        var before = portfolio.current();
        var ctx = new ArtifactContext(snapshot.getArtifactId(), 0, 0.0, 0.0, 0);
        return lifecycle.runChain(decision.getId(), new OrderPlan(nextAttempt.getSide(), remaining),
                now, before, ctx);
    }

    private Optional<PortfolioSnapshotEntity> findPendingSnapshot() {
        return snapshots.findAll().stream().filter(PortfolioSnapshotEntity::isLiquidationPending).findFirst();
    }

    private Optional<DecisionEntity> findInProgressClearDecision(String symbol) {
        List<DecisionEntity> candidates = decisions.findByExecutionStatus("IN_PROGRESS");
        return candidates.stream()
                .filter(d -> symbol.equals(d.getSymbol()) && "clear".equals(d.getLabel()))
                .findFirst();
    }
}
