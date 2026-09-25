package com.slice.wallet.domain;

/**
 * A closed set, owned by code. Adding a currency changes rounding, settlement and reporting, so
 * it should require a deploy and a conversation - not a row in a config table.
 */
public enum Currency {

    INR(100),
    USD(100);

    private final int minorUnitsPerMajor;

    Currency(int minorUnitsPerMajor) {
        this.minorUnitsPerMajor = minorUnitsPerMajor;
    }

    /** Minor units in one major unit: 100 paise to the rupee, 100 cents to the dollar. */
    public int minorUnitsPerMajor() {
        return minorUnitsPerMajor;
    }

    /** Decimal places this currency displays. Derived, so it cannot disagree with the scale. */
    public int decimalPlaces() {
        return Integer.toString(minorUnitsPerMajor - 1).length();
    }
}
