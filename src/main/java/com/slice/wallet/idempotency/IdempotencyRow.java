package com.slice.wallet.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** The {@code idempotency_records} row. */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRow {

    @Id
    @Column(name = "scope_key", length = 191, nullable = false, updatable = false)
    private String scopeKey;

    @Column(name = "fingerprint", length = 64, nullable = false, updatable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private IdempotencyState state;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** length 65535 maps to MySQL TEXT, matching the migration. */
    @Column(name = "response_body", length = 65535)
    private String responseBody;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRow() {
    }

    public static IdempotencyRow inFlight(String scopeKey, String fingerprint, Instant now) {
        IdempotencyRow row = new IdempotencyRow();
        row.scopeKey = scopeKey;
        row.fingerprint = fingerprint;
        row.state = IdempotencyState.IN_FLIGHT;
        row.createdAt = now;
        return row;
    }

    public void complete(int status, String body, String resourceId, Instant now) {
        this.state = IdempotencyState.COMPLETED;
        this.responseStatus = status;
        this.responseBody = body;
        this.resourceId = resourceId;
        this.completedAt = now;
    }

    public String scopeKey() {
        return scopeKey;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public IdempotencyState state() {
        return state;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public String responseBody() {
        return responseBody;
    }

    public String resourceId() {
        return resourceId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
