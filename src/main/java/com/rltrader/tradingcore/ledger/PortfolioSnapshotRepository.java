package com.rltrader.tradingcore.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshotEntity, String> {
}
