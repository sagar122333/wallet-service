package com.slice.wallet.domain;

/**
 * A wallet's lifecycle state.
 *
 * <p>{@code FROZEN} is why this is an enum rather than a boolean: a frozen wallet still accepts
 * credits (a refund owed to the customer must land) but refuses debits. A boolean
 * {@code active} could not express that asymmetry, and the asymmetry is the point.
 */
public enum WalletStatus {

    ACTIVE,

    /** Under investigation: credits allowed, debits refused. */
    FROZEN,

    /** Terminal. No movement in either direction; the balance must already be zero. */
    CLOSED;

    public boolean allowsCredit() {
        return this == ACTIVE || this == FROZEN;
    }

    public boolean allowsDebit() {
        return this == ACTIVE;
    }
}
