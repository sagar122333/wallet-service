package com.slice.wallet.web.dto;

import com.slice.wallet.domain.Currency;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /v1/wallets}.
 *
 * <p>{@code userId} is <b>optional and admin-only</b>. A normal caller omits it and the wallet is
 * created for the subject of their token; an onboarding service with {@code wallet:admin} may
 * name a different owner. Accepting it unconditionally would let any authenticated caller create
 * a wallet in someone else's name.
 */
public record CreateWalletRequest(
        @Size(max = 64, message = "userId must be at most 64 characters")
        String userId,

        @NotNull(message = "currency is required")
        Currency currency) {
}
