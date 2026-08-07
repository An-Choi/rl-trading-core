package com.rltrader.tradingcore.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventRepository extends JpaRepository<OrderEventEntity, Long> {

    List<OrderEventEntity> findByOrderIdOrderByIdAsc(long orderId);
}
