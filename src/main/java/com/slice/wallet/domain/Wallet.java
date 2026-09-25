package com.slice.wallet.domain;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.WalletException;

import java.time.Instant;
import java.util.Objects;

/**
 * A wallet's identity, owner, currency, status and current balance - and the rules about moving
 * money into and out of it.
 *
 * <p><b>It does not hold its history.</b> That is deliberate, and it is the single most important
 * modelling decision in this service. An aggregate that owns a {@code List<LedgerEntry>} forces
 * the persistence layer to load every entry the wallet has ever had in order to change the
 * balance by one rupee, and the cost of a top-up then grows with the wallet's lifetime. History
 * is a query, not a field. Nothing on this class needs it: a credit needs the balance, and a
 * debit needs the balance.
 *
 * <p><b>It does no locking.</b> Safety comes from the row lock the service takes with
 * {@code SELECT ... FOR UPDATE} before it loads this object, held until the transaction commits.
 * An in-JVM lock here would be worse than nothing: it would look like protection while doing
 * nothing at all across two instances of the service.
 *
 * <p><b>It is mutable, and that is safe because of its lifetime.</b> An instance is built inside
 * one transaction, from one locked row, mutated, written, and discarded. It is a scratchpad, not
 * shared state - it is never cached, never handed to another thread, and never reused across
 * requests.
 */
public final class Wallet {

    private final String walletId;
    private final String userId;
    private final Currency currency;
    private final WalletStatus status;
    private final Instant createdAt;

    private Money balance;

    public Wallet(String walletId, String userId, Currency currency, WalletStatus status,
                  Money balance, Instant createdAt) {
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.status = Objects.requireNonNull(status, "status");
        this.balance = Objects.requireNonNull(balance, "balance");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (balance.currency() != currency) {
            throw new IllegalArgumentException(
                    "wallet is " + currency + " but balance is " + balance.currency());
        }
    }

    public String walletId() {
        return walletId;
    }

    public String userId() {
        return userId;
    }

    public Currency currency() {
        return currency;
    }

    public WalletStatus status() {
        return status;
    }

    public Money balance() {
        return balance;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isOwnedBy(String candidateUserId) {
        return userId.equals(candidateUserId);
    }

    /**
     * Adds money and returns the posting to write.
     *
     * <p>A credit cannot fail on funds, so the only checks are the status and the currency. Note
     * that a FROZEN wallet still accepts credits: money the customer is owed must be able to
     * land even while their account is under investigation.
     */
    public Posting credit(Money amount, PostingContext context) {
        requireSameCurrency(amount);
        if (!status.allowsCredit()) {
            throw new WalletException(ErrorCode.WALLET_NOT_ACTIVE,
                    "wallet is " + status + " and cannot be credited");
        }
        balance = balance.plus(amount);       // throws on overflow rather than wrapping
        return new Posting(entry(EntryType.CREDIT, amount, context), balance);
    }

    /**
     * Removes money and returns the posting to write, or throws if the balance is insufficient.
     *
     * <p>The guard below is the no-overdraft invariant. Everything else about it is plumbing -
     * and it only means anything because the caller is holding this wallet's row lock while it
     * runs. Read the balance outside the lock and this is a check-then-act race in which two
     * spends both see enough money and both succeed.
     */
    public Posting debit(Money amount, PostingContext context) {
        requireSameCurrency(amount);
        if (!status.allowsDebit()) {
            throw new WalletException(ErrorCode.WALLET_NOT_ACTIVE,
                    "wallet is " + status + " and cannot be debited");
        }
        if (balance.isLessThan(amount)) {
            throw WalletException.insufficientFunds(
                    "balance " + balance + " is less than " + amount);
        }
        balance = balance.minus(amount);
        return new Posting(entry(EntryType.DEBIT, amount, context), balance);
    }

    private LedgerEntry entry(EntryType type, Money amount, PostingContext c) {
        return new LedgerEntry(c.entryId(), walletId, type, amount, balance.minor(),
                c.requestId(), c.transferId(), c.reversalOf(), c.reason(),
                c.initiatorType(), c.initiatedBy(), c.at());
    }

    private void requireSameCurrency(Money amount) {
        if (amount.currency() != currency) {
            throw new WalletException(ErrorCode.CURRENCY_MISMATCH,
                    "wallet is " + currency + ", amount is " + amount.currency());
        }
    }

    @Override
    public boolean equals(Object o) {
        // An entity: identity is the id. Two objects describing the same wallet are the same
        // wallet even if their balances differ by a microsecond.
        return o instanceof Wallet w && w.walletId.equals(walletId);
    }

    @Override
    public int hashCode() {
        return walletId.hashCode();
    }
}
