package com.slice.wallet.domain;

/**
 * Direction of a ledger entry, from the wallet's point of view. The amount itself is always
 * positive; this is what gives it a sign.
 */
public enum EntryType {

    CREDIT,
    DEBIT;

    public EntryType opposite() {
        return this == CREDIT ? DEBIT : CREDIT;
    }

    /** +amount for a credit, -amount for a debit. The fold the reconciliation view computes. */
    public long sign() {
        return this == CREDIT ? 1L : -1L;
    }
}
