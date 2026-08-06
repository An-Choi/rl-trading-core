package com.rltrader.tradingcore.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<DecisionEntity, Long> {
}
