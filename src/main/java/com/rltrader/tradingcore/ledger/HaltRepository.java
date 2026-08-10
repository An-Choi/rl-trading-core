package com.rltrader.tradingcore.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HaltRepository extends JpaRepository<HaltEntity, Long> {

    List<HaltEntity> findByClearedAtIsNull();
}
