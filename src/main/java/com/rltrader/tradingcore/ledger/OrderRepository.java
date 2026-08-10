package com.rltrader.tradingcore.ledger;

import com.rltrader.tradingcore.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    List<OrderEntity> findByStatusIn(Collection<OrderStatus> statuses);

    Optional<OrderEntity> findTopByDecisionIdOrderByAttemptDesc(long decisionId);

    Optional<OrderEntity> findByDecisionIdAndStatus(long decisionId, OrderStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id")
    Optional<OrderEntity> findByIdForUpdate(long id);

    @Query("select coalesce(sum(o.accountedFilledQty), 0) from OrderEntity o where o.decisionId = :decisionId")
    long sumAccountedFilledQtyByDecisionId(long decisionId);

    @Query("select coalesce(sum(o.accountedFillNotional), 0) from OrderEntity o where o.decisionId = :decisionId")
    BigDecimal sumAccountedFillNotionalByDecisionId(long decisionId);
}
