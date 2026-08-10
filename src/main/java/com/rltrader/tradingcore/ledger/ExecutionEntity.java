package com.rltrader.tradingcore.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "executions")
public class ExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "decision_id", nullable = false)
    private Long decisionId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "fill_price", nullable = false)
    private BigDecimal fillPrice;

    @Column(name = "shares_delta", nullable = false)
    private BigDecimal sharesDelta;

    @Column(name = "units_after", nullable = false)
    private int unitsAfter;

    @Column(name = "shares_after", nullable = false)
    private BigDecimal sharesAfter;

    @Column(name = "cash_after", nullable = false)
    private BigDecimal cashAfter;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(Long decisionId) {
        this.decisionId = decisionId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getFillPrice() {
        return fillPrice;
    }

    public void setFillPrice(double fillPrice) {
        this.fillPrice = BigDecimal.valueOf(fillPrice);
    }

    public BigDecimal getSharesDelta() {
        return sharesDelta;
    }

    public void setSharesDelta(double sharesDelta) {
        this.sharesDelta = BigDecimal.valueOf(sharesDelta);
    }

    public int getUnitsAfter() {
        return unitsAfter;
    }

    public void setUnitsAfter(int unitsAfter) {
        this.unitsAfter = unitsAfter;
    }

    public BigDecimal getSharesAfter() {
        return sharesAfter;
    }

    public void setSharesAfter(double sharesAfter) {
        this.sharesAfter = BigDecimal.valueOf(sharesAfter);
    }

    public BigDecimal getCashAfter() {
        return cashAfter;
    }

    public void setCashAfter(double cashAfter) {
        this.cashAfter = BigDecimal.valueOf(cashAfter);
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
