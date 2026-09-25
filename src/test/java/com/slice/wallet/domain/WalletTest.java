package com.slice.wallet.domain;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.WalletException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The invariants, tested with no Spring, no database and no HTTP.
 *
 * <p>These run in milliseconds and they are where the rules actually live, so they are the tests
 * to write first - the integration suite then only has to prove the wiring.
 */
class WalletTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private Wallet wallet(long balanceMinor, WalletStatus status) {
        return new Wallet("wal-1", "user-a", Currency.INR, status,
                Money.ofMinor(balanceMinor, Currency.INR), NOW);
    }

    private PostingContext context() {
        return PostingContext.standalone("entry-1", "req-1", InitiatorType.USER, "user-a", NOW);
    }

    @Test
    @DisplayName("a debit within the balance records the balance after it")
    void debitWithinBalance() {
        Wallet wallet = wallet(50_000, WalletStatus.ACTIVE);

        Posting posting = wallet.debit(Money.ofMajor(120, Currency.INR), context());

        assertEquals(38_000L, wallet.balance().minor());
        assertEquals(38_000L, posting.entry().balanceAfterMinor(), "the audit anchor");
        assertEquals(EntryType.DEBIT, posting.entry().type());
        assertEquals("user-a", posting.entry().initiatedBy(), "who moved the money is recorded");
    }

    @Test
    @DisplayName("a debit beyond the balance is refused and changes nothing")
    void debitBeyondBalanceIsRefused() {
        Wallet wallet = wallet(10_000, WalletStatus.ACTIVE);

        WalletException e = assertThrows(WalletException.class,
                () -> wallet.debit(Money.ofMinor(10_001, Currency.INR), context()));

        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e.code());
        assertEquals(10_000L, wallet.balance().minor(), "a refused debit leaves the balance alone");
    }

    @Test
    @DisplayName("a wallet refuses an amount in another currency")
    void currencyMustMatch() {
        Wallet wallet = wallet(10_000, WalletStatus.ACTIVE);

        WalletException e = assertThrows(WalletException.class,
                () -> wallet.credit(Money.ofMinor(500, Currency.USD), context()));

        assertEquals(ErrorCode.CURRENCY_MISMATCH, e.code());
    }

    @Test
    @DisplayName("a frozen wallet accepts credits but refuses debits")
    void frozenIsAsymmetric() {
        Wallet frozen = wallet(10_000, WalletStatus.FROZEN);

        frozen.credit(Money.ofMajor(1, Currency.INR), context());
        assertEquals(10_100L, frozen.balance().minor(), "money owed to the customer must still land");

        WalletException e = assertThrows(WalletException.class,
                () -> frozen.debit(Money.ofMajor(1, Currency.INR), context()));
        assertEquals(ErrorCode.WALLET_NOT_ACTIVE, e.code());
    }

    @Test
    @DisplayName("a reversal entry must carry a reason")
    void reversalRequiresReason() {
        Wallet wallet = wallet(10_000, WalletStatus.ACTIVE);
        PostingContext withReason = context().asReversalOf("original-1", "duplicate charge");

        Posting posting = wallet.credit(Money.ofMajor(1, Currency.INR), withReason);

        assertEquals("original-1", posting.entry().reversalOf());
        assertEquals("duplicate charge", posting.entry().reason());
        assertThrows(NullPointerException.class, () -> context().asReversalOf("original-1", null),
                "a refund with no recorded reason is unauditable");
    }
}
