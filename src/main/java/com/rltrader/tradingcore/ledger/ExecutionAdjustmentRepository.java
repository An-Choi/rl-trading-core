package com.rltrader.tradingcore.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionAdjustmentRepository extends JpaRepository<ExecutionAdjustmentEntity, Long> {
}
