package com.slice.wallet.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An exact monetary amount: an integral count of minor units, tagged with a currency.
 *
 * <p><b>Never a double or a float.</b> Binary floating point cannot represent 0.1, so repeated
 * addition drifts and two ledgers that should agree stop agreeing. {@code BigDecimal} is exact
 * but invites a forgotten {@code RoundingMode} and buys nothing here, because a paise is already
 * the smallest unit that exists. It appears in exactly one place - {@link #toDecimal()} and
 * {@link #ofDecimal} at the API boundary - and never inside the domain.
 *
 * <p><b>Non-negative by construction.</b> The private constructor is the only way in and it
 * throws below zero, so no negative {@code Money} exists anywhere in the program, which means no
 * code downstream has to check for one. {@code Math.addExact} / {@code subtractExact} /
 * {@code multiplyExact} close the last hole: a silent overflow would wrap to a negative and
 * defeat the guarantee entirely.
 */
public final class Money implements Comparable<Money> {

    private final long minor;
    private final Currency currency;

    private Money(long minor, Currency currency) {
        if (minor < 0) {
            throw new IllegalArgumentException("money cannot be negative: " + minor);
        }
        this.minor = minor;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinor(long minor, Currency currency) {
        return new Money(minor, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public static Money ofMajor(long major, Currency currency) {
        return new Money(Math.multiplyExact(major, currency.minorUnitsPerMajor()), currency);
    }

    public long minor() {
        return minor;
    }

    public Currency currency() {
        return currency;
    }

    public boolean isZero() {
        return minor == 0L;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    /**
     * Subtraction. Throws rather than returning a negative, so a caller that might go below zero
     * has to ask {@link #isLessThan} first - under whatever lock protects the balance.
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return minor < other.minor;
    }

    /**
     * The human-readable form, for display only.
     *
     * <p>Amounts cross the wire as an integer count of minor units. This is rendered as a
     * <b>string</b> in responses, never as a JSON number: emit 350.00 as a number and some
     * client's parser turns it into a double, and the drift starts on their side instead of
     * ours.
     */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minor)
                .movePointLeft(currency.decimalPlaces())
                .setScale(currency.decimalPlaces());
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            // Silently adding rupees to dollars is the bug you find in a reconciliation three
            // months later, when nobody remembers the deploy that caused it.
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    @Override
    public boolean equals(Object o) {
        // Both fields: 100 INR is not 100 USD, and this type is compared in tests and used as a
        // map value.
        return o instanceof Money m && m.minor == minor && m.currency == currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(minor, currency);
    }

    @Override
    public String toString() {
        return currency + " " + toDecimal().toPlainString();
    }
}
