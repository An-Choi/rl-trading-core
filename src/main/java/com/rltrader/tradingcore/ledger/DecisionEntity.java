package com.rltrader.tradingcore.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "decisions",
       uniqueConstraints = @UniqueConstraint(name = "uq_decisions_symbol_bar",
                                             columnNames = {"symbol", "bar_ts"}))
public class DecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String symbol;

    @Column(name = "bar_ts", nullable = false)
    private OffsetDateTime barTs;

    @Column(nullable = false)
    private int action;

    @Column(nullable = false, length = 16)
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_mask", nullable = false, columnDefinition = "jsonb")
    private String actionMask;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String observation;

    @Column(name = "bar_close", nullable = false)
    private double barClose;

    @Column(name = "artifact_id", nullable = false, length = 64)
    private String artifactId;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "execution_status", nullable = false, length = 16)
    private String executionStatus = "COMPLETED";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public OffsetDateTime getBarTs() {
        return barTs;
    }

    public void setBarTs(OffsetDateTime barTs) {
        this.barTs = barTs;
    }

    public int getAction() {
        return action;
    }

    public void setAction(int action) {
        this.action = action;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getActionMask() {
        return actionMask;
    }

    public void setActionMask(String actionMask) {
        this.actionMask = actionMask;
    }

    public String getObservation() {
        return observation;
    }

    public void setObservation(String observation) {
        this.observation = observation;
    }

    public double getBarClose() {
        return barClose;
    }

    public void setBarClose(double barClose) {
        this.barClose = barClose;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(String executionStatus) {
        this.executionStatus = executionStatus;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
