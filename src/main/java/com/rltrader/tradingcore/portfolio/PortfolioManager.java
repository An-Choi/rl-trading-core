package com.rltrader.tradingcore.portfolio;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradingGrid;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PortfolioManager {

    private static final Logger log = LoggerFactory.getLogger(PortfolioManager.class);
    private static final Set<OrderStatus> NON_TERMINAL =
            EnumSet.complementOf(EnumSet.of(OrderStatus.FILLED, OrderStatus.CANCELLED,
                    OrderStatus.REJECTED, OrderStatus.ABORTED));

    private final PortfolioSnapshotRepository snapshots;
    private final TradingProperties props;
    private final OrderRepository orders;
    private final DecisionRepository decisions;
    private final Clock clock;
    private final AtomicReference<PortfolioState> state = new AtomicReference<>();

    public PortfolioManager(PortfolioSnapshotRepository snapshots, TradingProperties props,
            OrderRepository orders, DecisionRepository decisions, Clock clock) {
        this.snapshots = snapshots;
        this.props = props;
        this.orders = orders;
        this.decisions = decisions;
        this.clock = clock;
    }

    public PortfolioState current() {
        PortfolioState s = state.get();
        if (s == null) {
            throw new IllegalStateException("portfolio not restored yet");
        }
        return s;
    }

    /**
     * restore()가 이미 실행되어 메모리 상태가 있는지 — 리뷰 수정(Important):
     * LiquidationService.resume()처럼 current()를 호출하기 전에 부트 순서를 명시적으로
     * 검증하려는 호출부를 위한 비-throwing 체크.
     */
    public boolean isRestored() {
        return state.get() != null;
    }

    /** 메모리만 갱신 — DB 반영은 사이클 트랜잭션의 몫, 호출은 커밋 후 (spec §2). */
    public void apply(PortfolioState next) {
        state.set(next);
    }

    public void restore(ArtifactContext ctx) {
        var snapshot = snapshots.findById(props.symbol());
        if (snapshot.isEmpty()) {
            state.set(PortfolioState.flat(props.dryRunInitialCash()));
            return;
        }
        var s = snapshot.get();
        if (!ctx.artifactId().equals(s.getArtifactId())) {
            log.warn("snapshot artifact {} != current {} — flat으로 초기화 (다른 모델의 포지션 미승계)",
                    s.getArtifactId(), ctx.artifactId());
            state.set(PortfolioState.flat(props.dryRunInitialCash()));
            return;
        }
        ZonedDateTime entry = s.getEntryBarTs() != null
                ? s.getEntryBarTs().atZoneSameInstant(TradingGrid.KST) : null;
        state.set(new PortfolioState(s.getUnits(), s.getShares().doubleValue(), s.getCash().doubleValue(), entry));
    }

    /**
     * live 전용 복원(Task 14, spec §7 ④) — artifact 불일치 여부와 무관하게 저장된 원본 상태
     * 그대로 메모리에 올린다. {@code restore()}처럼 불일치 시 즉시 flat으로 되돌리면, 뒤이은
     * open-order 복구(⑤)가 flat 기준으로 회계를 쌓아 snapshot을 오염시킨다 — live 기동은 복구를
     * 원본 위에서 먼저 끝내고, artifact 판정(⑦)은 그 뒤에 별도로 확정한다.
     *
     * <p>이 시점에 {@code artifactMismatch}를 캡처해 반환값에 실어 보존한다 — 복구
     * finalization(RECOVERY 모드)이 snapshot을 다시 쓰므로, 복구가 끝난 뒤에 다시 비교하면
     * 증거(원래 저장돼 있던 artifact_id)가 이미 사라져 있을 수 있다. 비교 기준:
     * <ul>
     *   <li>snapshot이 있으면 {@code snapshot.artifactId} vs {@code ctx.artifactId()}.</li>
     *   <li>snapshot이 없는데 미종결 주문이 있으면(첫 finalization 전 크래시 — snapshot 행 자체가
     *       없다) 그 주문이 속한 decision의 {@code artifact_id}가 유일한 provenance.</li>
     *   <li>둘 다 없으면(진짜 첫 기동) mismatch 아님.</li>
     * </ul>
     */
    public RawRestoreResult restoreRaw(ArtifactContext ctx) {
        var snapshot = snapshots.findById(props.symbol());
        if (snapshot.isEmpty()) {
            state.set(PortfolioState.flat(props.dryRunInitialCash()));
            String priorArtifactId = pendingOrderArtifactId();
            boolean mismatch = priorArtifactId != null && !ctx.artifactId().equals(priorArtifactId);
            return new RawRestoreResult(mismatch, null);
        }
        var s = snapshot.get();
        ZonedDateTime entry = s.getEntryBarTs() != null
                ? s.getEntryBarTs().atZoneSameInstant(TradingGrid.KST) : null;
        state.set(new PortfolioState(s.getUnits(), s.getShares().doubleValue(), s.getCash().doubleValue(), entry));
        boolean mismatch = !ctx.artifactId().equals(s.getArtifactId());
        return new RawRestoreResult(mismatch, s.getArtifactId());
    }

    /**
     * live 전용(Task 14, spec §7 ⑦) — artifact 불일치가 확인됐고 KIS 실보유가 0일 때만 호출된다
     * (호출부 BootRecoveryService가 그 판정을 이미 끝냈다는 전제). 메모리를 flat으로 되돌리고,
     * snapshot.artifact_id를 새 값(ctx)으로 갱신한다 — 이 갱신은 이 경로에서만 일어난다
     * (RECOVERY finalization의 saveSnapshot은 절대 ctx를 쓰지 않는다, OrderFinalizationService
     * 참고). snapshot 행이 없었다면(restoreRaw가 이미 flat으로 시작한 경우) 새로 만든다 — 어느
     * 쪽이든 "실보유 0 확인 후 이 artifact로 깨끗이 다시 시작한다"는 사실 자체를 기록해 둔다.
     */
    public void resetFlatAfterArtifactMismatch(ArtifactContext ctx) {
        PortfolioState flat = PortfolioState.flat(props.dryRunInitialCash());
        state.set(flat);
        PortfolioSnapshotEntity s = snapshots.findById(props.symbol())
                .orElseGet(PortfolioSnapshotEntity::new);
        s.setSymbol(props.symbol());
        s.setUnits(flat.units());
        s.setShares(flat.shares());
        s.setCash(flat.cash());
        s.setEntryBarTs(null);
        s.setArtifactId(ctx.artifactId());
        s.setUpdatedAt(OffsetDateTime.now(clock));
        s.setLiquidationPending(false);
        snapshots.save(s);
    }

    /** snapshot이 없을 때의 유일한 provenance — 이 심볼의 미종결 주문이 속한 decision의 artifact_id. */
    private String pendingOrderArtifactId() {
        for (OrderEntity o : orders.findByStatusIn(NON_TERMINAL)) {
            if (props.symbol().equals(o.getSymbol())) {
                return decisions.findById(o.getDecisionId()).orElseThrow().getArtifactId();
            }
        }
        return null;
    }

    /**
     * restoreRaw()의 판정 캡슐 — mismatch 여부 + 그 판정의 근거가 된 artifact_id(진단·로그용,
     * snapshot이 없었으면 decision에서 가져온 값, 둘 다 없으면 null).
     */
    public record RawRestoreResult(boolean artifactMismatch, String priorArtifactId) {}
}
