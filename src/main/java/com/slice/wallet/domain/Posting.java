package com.slice.wallet.domain;

/**
 * A movement that has passed every check but has not been written anywhere yet: the entry to
 * insert, and the balance the wallet must be left at.
 *
 * <p>Separating "decide" from "write" is what lets a transfer validate both of its legs before
 * either row is touched. The database transaction is what actually makes the pair atomic - but
 * the common failure, insufficient funds, then never depends on a rollback to undo a write that
 * should not have happened.
 */
public record Posting(LedgerEntry entry, Money balanceAfter) {
}
