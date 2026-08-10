package com.rltrader.tradingcore.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<DecisionEntity, Long> {

    List<DecisionEntity> findByExecutionStatus(String status);
}
