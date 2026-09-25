package com.slice.wallet.persistence;

import com.slice.wallet.domain.Currency;
import com.slice.wallet.domain.Money;
import com.slice.wallet.domain.Wallet;
import com.slice.wallet.domain.WalletStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code wallets} row.
 *
 * <p><b>No association to the ledger.</b> There is no {@code @OneToMany} here, and that absence
 * is the design. An eager collection would load every entry the wallet has ever had on every
 * balance read and re-merge all of them on every write; a lazy one would either throw outside a
 * session or silently issue an extra query per wallet. History is served by
 * {@link LedgerEntryRepository}'s keyset query instead, which reads exactly one page.
 *
 * <p>Kept separate from the domain {@link Wallet} because Hibernate needs a no-arg constructor
 * and field access, and because the domain has no business importing
 * {@code jakarta.persistence}. The conversion is two small methods at the bottom of this class,
 * which is the entire cost of that separation.
 */
@Entity
@Table(name = "wallets")
public class WalletRow {

    @Id
    @Column(name = "wallet_id", length = 36, nullable = false, updatable = false)
    private String walletId;

    @Column(name = "user_id", length = 64, nullable = false, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 8, nullable = false, updatable = false)
    private Currency currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    /**
     * Optimistic-lock counter.
     *
     * <p>The write paths take a pessimistic row lock, so on those paths this is redundant - and
     * it is here precisely for the paths that are not written yet. If someone later adds a code
     * path that updates a balance without {@code SELECT ... FOR UPDATE}, this turns a silent
     * lost update into an {@code OptimisticLockException}. A guarantee that only holds while
     * every future author remembers a convention is not a guarantee.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private WalletStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Hibernate sets every field reflectively afterwards. */
    protected WalletRow() {
    }

    private WalletRow(String walletId, String userId, Currency currency, Instant now) {
        this.walletId = walletId;
        this.userId = userId;
        this.currency = currency;
        this.balanceMinor = 0L;
        this.status = WalletStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A brand-new wallet, always at a zero balance. Money only ever arrives via the ledger. */
    public static WalletRow create(String userId, Currency currency, Instant now) {
        return new WalletRow(UUID.randomUUID().toString(), userId, currency, now);
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

    public long balanceMinor() {
        return balanceMinor;
    }

    public WalletStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    public Wallet toDomain() {
        return new Wallet(walletId, userId, currency, status,
                Money.ofMinor(balanceMinor, currency), createdAt);
    }

    /**
     * Writes the balance the domain object arrived at.
     *
     * <p>A single method rather than a setter, so there is exactly one line in the codebase that
     * can change a balance, and it is grep-able. It must only ever be called on a row loaded
     * with {@code findForUpdate} inside the same transaction.
     */
    public void applyBalance(Money newBalance, Instant now) {
        if (newBalance.currency() != currency) {
            throw new IllegalArgumentException("wallet is " + currency + ", got " + newBalance.currency());
        }
        this.balanceMinor = newBalance.minor();
        this.updatedAt = now;
    }
}
