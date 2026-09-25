package com.slice.wallet.web.dto;

import com.slice.wallet.domain.Currency;
import com.slice.wallet.domain.Money;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body for a credit or a debit.
 *
 * <p><b>An integer count of minor units, not a decimal.</b> {@code "amountMinor": 35000} cannot
 * be misread; {@code "amount": 350.00} can be turned into a double by an intermediate parser
 * before it ever reaches us. It is a {@code Long} rather than a {@code long} so that a missing
 * field is a clear "amountMinor is required" instead of silently defaulting to zero.
 *
 * <p>The currency is stated explicitly even though the wallet already knows it. Inferring it
 * would mean a client that believes it is sending dollars to an INR wallet gets silent success
 * instead of a rejection.
 */
public record AmountRequest(
        @NotNull(message = "amountMinor is required")
        @Positive(message = "amountMinor must be greater than zero")
        Long amountMinor,

        @NotNull(message = "currency is required")
        Currency currency) {

    public Money toMoney() {
        return Money.ofMinor(amountMinor, currency);
    }
}
