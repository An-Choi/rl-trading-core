package com.rltrader.tradingcore.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "portfolio_snapshots")
public class PortfolioSnapshotEntity {

    @Id
    @Column(nullable = false, length = 6)
    private String symbol;

    @Column(nullable = false)
    private int units;

    @Column(nullable = false)
    private BigDecimal shares;

    @Column(nullable = false)
    private BigDecimal cash;

    @Column(name = "entry_bar_ts")
    private OffsetDateTime entryBarTs;

    @Column(name = "artifact_id", nullable = false, length = 64)
    private String artifactId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "liquidation_pending", nullable = false)
    private boolean liquidationPending;

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public int getUnits() {
        return units;
    }

    public void setUnits(int units) {
        this.units = units;
    }

    public BigDecimal getShares() {
        return shares;
    }

    public void setShares(double shares) {
        this.shares = BigDecimal.valueOf(shares);
    }

    public BigDecimal getCash() {
        return cash;
    }

    public void setCash(double cash) {
        this.cash = BigDecimal.valueOf(cash);
    }

    public OffsetDateTime getEntryBarTs() {
        return entryBarTs;
    }

    public void setEntryBarTs(OffsetDateTime entryBarTs) {
        this.entryBarTs = entryBarTs;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isLiquidationPending() {
        return liquidationPending;
    }

    public void setLiquidationPending(boolean liquidationPending) {
        this.liquidationPending = liquidationPending;
    }
}
