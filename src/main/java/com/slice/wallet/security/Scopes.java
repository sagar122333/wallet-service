package com.slice.wallet.security;

/**
 * The scopes this service understands.
 *
 * <p>Spring Security prefixes JWT scopes with {@code SCOPE_} when it converts them to
 * authorities, so the {@code HAS_*} constants exist to be pasted into {@code @PreAuthorize}
 * without hand-writing that prefix at twenty call sites and getting one of them wrong.
 *
 * <p>The interesting one is {@link #REVERSE}. A customer reversing their own spend is a refund
 * they granted themselves, so reversal is an operator action with a mandatory reason - which is
 * what gives this API a genuine 403 path rather than a decorative one.
 */
public final class Scopes {

    /** Read your own wallets, balances and history. */
    public static final String READ = "wallet:read";

    /** Move money in or out of a wallet you own. */
    public static final String WRITE = "wallet:write";

    /** Post a compensating entry against someone's transaction. Operators only. */
    public static final String REVERSE = "wallet:reverse";

    /** Act on any wallet regardless of owner. Every use is logged at WARN. */
    public static final String ADMIN = "wallet:admin";

    public static final String HAS_READ = "hasAuthority('SCOPE_" + READ + "')";
    public static final String HAS_WRITE = "hasAuthority('SCOPE_" + WRITE + "')";
    public static final String HAS_REVERSE = "hasAuthority('SCOPE_" + REVERSE + "')";
    public static final String HAS_ADMIN = "hasAuthority('SCOPE_" + ADMIN + "')";

    private Scopes() {
    }
}
