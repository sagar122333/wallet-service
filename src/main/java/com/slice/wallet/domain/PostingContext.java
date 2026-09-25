package com.slice.wallet.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Everything about a movement that is not the amount: its identity, its idempotency key, its
 * provenance and who caused it.
 *
 * <p>It exists so {@link Wallet#credit} and {@link Wallet#debit} take two arguments instead of
 * nine. A long parameter list of same-typed {@code String}s is a defect waiting to happen -
 * transpose {@code requestId} and {@code transferId} at one call site and nothing complains.
 */
public record PostingContext(String entryId,
                             String requestId,
                             String transferId,
                             String reversalOf,
                             String reason,
                             InitiatorType initiatorType,
                             String initiatedBy,
                             Instant at) {

    public PostingContext {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(initiatorType, "initiatorType");
        Objects.requireNonNull(initiatedBy, "initiatedBy");
        Objects.requireNonNull(at, "at");
    }

    /** A standalone movement: a top-up or a spend. */
    public static PostingContext standalone(String entryId, String requestId,
                                            InitiatorType initiatorType, String initiatedBy,
                                            Instant at) {
        return new PostingContext(entryId, requestId, null, null, null, initiatorType, initiatedBy, at);
    }

    /** One leg of a transfer. Both legs share the transferId and the caller's requestId. */
    public PostingContext asTransferLeg(String transferId) {
        return new PostingContext(entryId, requestId, Objects.requireNonNull(transferId),
                reversalOf, reason, initiatorType, initiatedBy, at);
    }

    /** A compensating entry. The reason is mandatory and the schema enforces it. */
    public PostingContext asReversalOf(String originalEntryId, String why) {
        return new PostingContext(entryId, requestId, transferId,
                Objects.requireNonNull(originalEntryId, "originalEntryId"),
                Objects.requireNonNull(why, "reason"),
                initiatorType, initiatedBy, at);
    }

    /** Same context, different entry id - used when one request posts several entries. */
    public PostingContext withEntryId(String newEntryId) {
        return new PostingContext(newEntryId, requestId, transferId, reversalOf, reason,
                initiatorType, initiatedBy, at);
    }
}
