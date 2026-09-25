package com.slice.wallet.security;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.WalletException;

import java.util.Objects;
import java.util.Set;

/**
 * The authenticated caller, as this service's own code sees it.
 *
 * <p>A deliberate translation of Spring Security's {@code JwtAuthenticationToken} into a type
 * the domain and service layers can hold without importing Spring Security. It carries the two
 * facts that matter - who you are, and what you are allowed to attempt - and nothing else.
 *
 * <p><b>What is not here, on purpose: any wallet id.</b> A token says who you are; it must never
 * say what you own. If a claim could carry a wallet id, the client would control authorisation.
 * Ownership is a {@code SELECT}.
 */
public record Caller(String userId, Set<String> scopes) {

    public Caller {
        Objects.requireNonNull(userId, "userId");
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    /** True when this caller may act on wallets they do not own. */
    public boolean isAdmin() {
        return hasScope(Scopes.ADMIN);
    }

    /**
     * Enforced in the service layer as well as at the filter chain.
     *
     * <p>Not belt-and-braces for its own sake: the filter chain only guards HTTP entry points,
     * and the day this service grows a Kafka consumer or a scheduled job, that code reaches the
     * service directly. A check that lives only at the edge is a check that the second entry
     * point silently skips.
     */
    public void requireScope(String scope) {
        if (!hasScope(scope)) {
            throw new WalletException(ErrorCode.FORBIDDEN, "this token is missing the scope " + scope);
        }
    }
}
