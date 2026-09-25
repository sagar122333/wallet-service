package com.slice.wallet.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable movement of money.
 *
 * <p>The ledger is the source of truth; a wallet's balance is a cached fold over these. Nothing
 * ever updates or deletes an entry - a mistake is corrected by appending a compensating entry
 * whose {@link #reversalOf()} points back at the original.
 *
 * <p>{@code transferId}, {@code reversalOf} and {@code reason} are null most of the time. In a
 * larger model they would collapse into a sealed {@code Provenance} type - two vocabularies that
 * look alike are not one type - but at this size a nullable trio costs less than the ceremony,
 * and the database enforces the one rule that matters between them: a reason exists if and only
 * if this entry is a reversal.
 */
public record LedgerEntry(String entryId,
                          String walletId,
                          EntryType type,
                          Money amount,
                          long balanceAfterMinor,
                          String requestId,
                          String transferId,
                          String reversalOf,
                          String reason,
                          InitiatorType initiatorType,
                          String initiatedBy,
                          Instant at) {

    public LedgerEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(initiatorType, "initiatorType");
        Objects.requireNonNull(initiatedBy, "initiatedBy");
        Objects.requireNonNull(at, "at");
        if (amount.isZero()) {
            throw new IllegalArgumentException("a zero-amount ledger entry is meaningless");
        }
        if ((reversalOf == null) != (reason == null)) {
            // Mirrors ck_ledger_reason_iff_reversal. Checked here too so a unit test catches it
            // without a database.
            throw new IllegalArgumentException("reason must be present exactly when reversalOf is");
        }
    }

    /** This entry's contribution to the wallet's balance: positive for a credit. */
    public long signedMinor() {
        return type.sign() * amount.minor();
    }

    public boolean isTransferLeg() {
        return transferId != null;
    }

    public boolean isReversal() {
        return reversalOf != null;
    }
}
