package com.rltrader.tradingcore.reconcile;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRepository extends JpaRepository<ReconciliationEntity, Long> {
}
