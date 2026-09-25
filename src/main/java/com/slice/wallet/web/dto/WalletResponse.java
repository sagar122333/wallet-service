package com.slice.wallet.web.dto;

import com.slice.wallet.domain.Currency;
import com.slice.wallet.domain.Wallet;
import com.slice.wallet.domain.WalletStatus;

import java.time.Instant;

/**
 * A wallet, as the API presents it.
 *
 * <p>{@code balanceMinor} is the authoritative field - an integer count of paise. {@code balance}
 * is the same number rendered for humans, as a <b>string</b>: emit it as a JSON number and some
 * client's parser turns it into a double, and the drift starts on their side instead of ours.
 */
public record WalletResponse(String walletId,
                             String userId,
                             Currency currency,
                             long balanceMinor,
                             String balance,
                             WalletStatus status,
                             Instant createdAt) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.walletId(),
                wallet.userId(),
                wallet.currency(),
                wallet.balance().minor(),
                wallet.balance().toDecimal().toPlainString(),
                wallet.status(),
                wallet.createdAt());
    }
}
