package com.rltrader.tradingcore.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<ExecutionEntity, Long> {
}
