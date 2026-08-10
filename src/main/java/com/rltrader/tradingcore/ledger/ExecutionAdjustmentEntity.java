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
@Table(name = "execution_adjustments")
public class ExecutionAdjustmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "from_filled_qty", nullable = false)
    private long fromFilledQty;

    @Column(name = "to_filled_qty", nullable = false)
    private long toFilledQty;

    @Column(name = "from_fill_notional", nullable = false)
    private BigDecimal fromFillNotional;

    @Column(name = "to_fill_notional", nullable = false)
    private BigDecimal toFillNotional;

    @Column(name = "qty_delta", nullable = false)
    private long qtyDelta;

    @Column(name = "notional_delta", nullable = false)
    private BigDecimal notionalDelta;

    @Column(nullable = false, length = 64)
    private String reason;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public long getFromFilledQty() {
        return fromFilledQty;
    }

    public void setFromFilledQty(long fromFilledQty) {
        this.fromFilledQty = fromFilledQty;
    }

    public long getToFilledQty() {
        return toFilledQty;
    }

    public void setToFilledQty(long toFilledQty) {
        this.toFilledQty = toFilledQty;
    }

    public BigDecimal getFromFillNotional() {
        return fromFillNotional;
    }

    public void setFromFillNotional(BigDecimal fromFillNotional) {
        this.fromFillNotional = fromFillNotional;
    }

    public BigDecimal getToFillNotional() {
        return toFillNotional;
    }

    public void setToFillNotional(BigDecimal toFillNotional) {
        this.toFillNotional = toFillNotional;
    }

    public long getQtyDelta() {
        return qtyDelta;
    }

    public void setQtyDelta(long qtyDelta) {
        this.qtyDelta = qtyDelta;
    }

    public BigDecimal getNotionalDelta() {
        return notionalDelta;
    }

    public void setNotionalDelta(BigDecimal notionalDelta) {
        this.notionalDelta = notionalDelta;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
