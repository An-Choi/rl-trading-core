package com.rltrader.tradingcore.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
    private double shares;

    @Column(nullable = false)
    private double cash;

    @Column(name = "entry_bar_ts")
    private OffsetDateTime entryBarTs;

    @Column(name = "artifact_id", nullable = false, length = 64)
    private String artifactId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

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

    public double getShares() {
        return shares;
    }

    public void setShares(double shares) {
        this.shares = shares;
    }

    public double getCash() {
        return cash;
    }

    public void setCash(double cash) {
        this.cash = cash;
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
}
