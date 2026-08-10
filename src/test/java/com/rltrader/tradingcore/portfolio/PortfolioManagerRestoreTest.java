package com.rltrader.tradingcore.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.predict.ArtifactContext;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PortfolioManagerRestoreTest extends AbstractIntegrationTest {

    @Autowired PortfolioSnapshotRepository snapshots;
    @Autowired PortfolioManager manager;

    @org.junit.jupiter.api.BeforeEach
    void cleanSnapshots() {
        snapshots.deleteAll();   // 테스트 간 순서 의존 제거
    }

    private static final ArtifactContext CTX =
            new ArtifactContext("ppo-fs3-x", 3, 0.199, 10_000.0, 5);

    private void saveSnapshot(String artifactId, OffsetDateTime entryBarTs) {
        PortfolioSnapshotEntity e = new PortfolioSnapshotEntity();
        e.setSymbol("005930");
        e.setUnits(2);
        e.setShares(0.05);
        e.setCash(6_000.0);
        e.setEntryBarTs(entryBarTs);
        e.setArtifactId(artifactId);
        e.setUpdatedAt(OffsetDateTime.now());
        snapshots.save(e);
    }

    @Test
    void restoreRecomputesBarsSinceEntryFromEntryBarTs() {
        saveSnapshot("ppo-fs3-x", OffsetDateTime.parse("2026-08-06T10:05:00+09:00"));
        manager.restore(CTX);
        PortfolioState state = manager.current();
        assertThat(state.units()).isEqualTo(2);
        // NUMERIC 왕복 무손실 확인 (spec §1 — DB가 회계의 진실, double은 관찰 투영)
        assertThat(state.shares()).isEqualTo(0.05);
        assertThat(state.cash()).isEqualTo(6_000.0);
        // 재시작 갭 복구: 진입 10:05 → 현재 bar 11:00이면 11
        assertThat(state.barsSinceEntry(
                ZonedDateTime.parse("2026-08-06T11:00:00+09:00[Asia/Seoul]"))).isEqualTo(11);
    }

    @Test
    void restoreWithDifferentArtifactResetsToFlat() {
        saveSnapshot("ppo-fs3-OLD", OffsetDateTime.parse("2026-08-06T10:05:00+09:00"));
        manager.restore(CTX);
        PortfolioState state = manager.current();
        assertThat(state.units()).isZero();
        assertThat(state.entryBarTs()).isNull();      // 다른 모델의 포지션 미승계 (spec §4)
        assertThat(state.cash()).isEqualTo(10_000.0); // config 초기 자본
    }

    @Test
    void restoreWithoutSnapshotStartsFlat() {
        manager.restore(CTX);
        assertThat(manager.current().units()).isZero();
    }
}
