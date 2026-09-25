package com.slice.wallet.web.dto;

import com.slice.wallet.domain.Currency;
import com.slice.wallet.domain.Money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /v1/transfers}. */
public record TransferRequest(
        @NotBlank(message = "fromWalletId is required")
        @Size(max = 36, message = "fromWalletId is not a wallet id")
        String fromWalletId,

        @NotBlank(message = "toWalletId is required")
        @Size(max = 36, message = "toWalletId is not a wallet id")
        String toWalletId,

        @NotNull(message = "amountMinor is required")
        @Positive(message = "amountMinor must be greater than zero")
        Long amountMinor,

        @NotNull(message = "currency is required")
        Currency currency) {

    public Money toMoney() {
        return Money.ofMinor(amountMinor, currency);
    }
}
