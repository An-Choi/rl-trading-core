package com.rltrader.tradingcore.portfolio;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.domain.TradingGrid;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PortfolioManager {

    private static final Logger log = LoggerFactory.getLogger(PortfolioManager.class);

    private final PortfolioSnapshotRepository snapshots;
    private final TradingProperties props;
    private final AtomicReference<PortfolioState> state = new AtomicReference<>();

    public PortfolioManager(PortfolioSnapshotRepository snapshots, TradingProperties props) {
        this.snapshots = snapshots;
        this.props = props;
    }

    public PortfolioState current() {
        PortfolioState s = state.get();
        if (s == null) {
            throw new IllegalStateException("portfolio not restored yet");
        }
        return s;
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
        state.set(new PortfolioState(s.getUnits(), s.getShares(), s.getCash(), entry));
    }
}
