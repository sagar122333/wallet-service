package com.slice.wallet.idempotency;

/** Lifecycle of an idempotency record. */
public enum IdempotencyState {

    /**
     * Claimed, still running. A duplicate arriving now has nothing to replay yet, so it gets a
     * 409 telling it to retry shortly - rather than being queued, which would hold a thread and
     * a connection open for a client that already has a retry loop.
     */
    IN_FLIGHT,

    /** Terminal and successful. The stored status and body are replayed verbatim. */
    COMPLETED
}
