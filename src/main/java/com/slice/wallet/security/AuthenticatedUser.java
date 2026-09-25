package com.slice.wallet.security;

import java.util.Objects;
import java.util.Set;

/**
 * What the user-management service tells us about a token: who holds it, and what they are
 * allowed to attempt.
 *
 * <p>Separate from {@link Caller} on purpose. This is the <i>wire answer</i> from another
 * service; {@code Caller} is this application's own notion of the current principal. Keeping
 * them apart means changing the identity provider's response shape touches one mapping line,
 * not every service method.
 *
 * <p>Note what is <b>not</b> here: any wallet id. A token says who you are; it must never say
 * what you own, or the client controls authorisation. Ownership is a {@code SELECT}.
 */
public record AuthenticatedUser(String userId, Set<String> scopes) {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        scopes = Set.copyOf(scopes);
    }

    public Caller toCaller() {
        return new Caller(userId, scopes);
    }
}
