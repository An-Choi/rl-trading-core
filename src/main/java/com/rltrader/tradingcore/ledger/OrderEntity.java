package com.rltrader.tradingcore.ledger;

import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "decision_id", nullable = false)
    private long decisionId;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(nullable = false, length = 6)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private OrderSide side;

    @Column(nullable = false)
    private long qty;

    @Column(name = "order_type", nullable = false, length = 16)
    private String orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "kis_order_no", length = 16)
    private String kisOrderNo;

    @Column(name = "krx_fwdg_ord_orgno", length = 16)
    private String krxFwdgOrdOrgno;

    @Column(name = "filled_qty", nullable = false)
    private long filledQty;

    @Column(name = "accounted_filled_qty", nullable = false)
    private long accountedFilledQty;

    @Column(name = "accounted_fill_notional", nullable = false)
    private BigDecimal accountedFillNotional;

    @Column(name = "avg_fill_price")
    private BigDecimal avgFillPrice;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(long decisionId) {
        this.decisionId = decisionId;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public LocalDate getTradingDate() {
        return tradingDate;
    }

    public void setTradingDate(LocalDate tradingDate) {
        this.tradingDate = tradingDate;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public long getQty() {
        return qty;
    }

    public void setQty(long qty) {
        this.qty = qty;
    }

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getKisOrderNo() {
        return kisOrderNo;
    }

    public void setKisOrderNo(String kisOrderNo) {
        this.kisOrderNo = kisOrderNo;
    }

    public String getKrxFwdgOrdOrgno() {
        return krxFwdgOrdOrgno;
    }

    public void setKrxFwdgOrdOrgno(String krxFwdgOrdOrgno) {
        this.krxFwdgOrdOrgno = krxFwdgOrdOrgno;
    }

    public long getFilledQty() {
        return filledQty;
    }

    public void setFilledQty(long filledQty) {
        this.filledQty = filledQty;
    }

    public long getAccountedFilledQty() {
        return accountedFilledQty;
    }

    public void setAccountedFilledQty(long accountedFilledQty) {
        this.accountedFilledQty = accountedFilledQty;
    }

    public BigDecimal getAccountedFillNotional() {
        return accountedFillNotional;
    }

    public void setAccountedFillNotional(BigDecimal accountedFillNotional) {
        this.accountedFillNotional = accountedFillNotional;
    }

    public BigDecimal getAvgFillPrice() {
        return avgFillPrice;
    }

    public void setAvgFillPrice(BigDecimal avgFillPrice) {
        this.avgFillPrice = avgFillPrice;
    }

    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(OffsetDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public OffsetDateTime getTerminalAt() {
        return terminalAt;
    }

    public void setTerminalAt(OffsetDateTime terminalAt) {
        this.terminalAt = terminalAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
