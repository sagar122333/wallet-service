package com.slice.wallet.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The money type's guarantees. Each test names the bug it prevents. */
class MoneyTest {

    @Test
    @DisplayName("a negative Money cannot be constructed at all")
    void negativeIsImpossible() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor(-1, Currency.INR),
                "if a negative cannot exist, no code downstream has to check for one");
    }

    @Test
    @DisplayName("subtracting below zero throws instead of returning a negative")
    void subtractingBelowZeroThrows() {
        Money ten = Money.ofMajor(10, Currency.INR);
        assertThrows(IllegalArgumentException.class, () -> ten.minus(Money.ofMajor(11, Currency.INR)),
                "callers must ask isLessThan first, under the lock");
    }

    @Test
    @DisplayName("overflow throws instead of wrapping to a negative")
    void overflowThrows() {
        Money huge = Money.ofMinor(Long.MAX_VALUE, Currency.INR);
        assertThrows(ArithmeticException.class, () -> huge.plus(Money.ofMinor(1, Currency.INR)),
                "a wrapped add is the one remaining way to smuggle in a negative balance");
        assertThrows(ArithmeticException.class, () -> Money.ofMajor(Long.MAX_VALUE / 10, Currency.INR),
                "and the same applies to constructing from major units");
    }

    @Test
    @DisplayName("arithmetic across currencies is refused")
    void mixedCurrencyIsRefused() {
        Money inr = Money.ofMinor(100, Currency.INR);
        Money usd = Money.ofMinor(100, Currency.USD);
        assertThrows(IllegalArgumentException.class, () -> inr.plus(usd));
        assertThrows(IllegalArgumentException.class, () -> inr.minus(usd));
        assertThrows(IllegalArgumentException.class, () -> inr.isLessThan(usd));
        assertThrows(IllegalArgumentException.class, () -> inr.compareTo(usd));
    }

    @Test
    @DisplayName("equality covers the currency, not just the amount")
    void equalityCoversCurrency() {
        assertNotEquals(Money.ofMinor(100, Currency.INR), Money.ofMinor(100, Currency.USD),
                "100 INR is not 100 USD");
        assertEquals(Money.ofMinor(100, Currency.INR), Money.ofMinor(100, Currency.INR));
        assertEquals(Money.ofMinor(100, Currency.INR).hashCode(),
                Money.ofMinor(100, Currency.INR).hashCode());
    }

    @Test
    @DisplayName("major units scale to minor units exactly")
    void majorScalesToMinor() {
        assertEquals(35000L, Money.ofMajor(350, Currency.INR).minor());
        assertEquals("350.00", Money.ofMajor(350, Currency.INR).toDecimal().toPlainString());
        assertEquals("0.07", Money.ofMinor(7, Currency.INR).toDecimal().toPlainString(),
                "seven paise renders with both decimal places");
        assertTrue(Money.zero(Currency.INR).isZero());
    }
}
