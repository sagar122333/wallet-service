package com.slice.wallet.security;

import java.util.Optional;

/**
 * Validates a bearer token against the user-management service and fetches that user's scopes.
 *
 * <p><b>This service verifies identity; it never issues it.</b> Password hashing, login, MFA and
 * refresh-token rotation belong to user-management. Our entire authentication responsibility is
 * "ask whether this token is good, and what it may do".
 *
 * <p>An interface with two implementations - {@link StubUserManagementClient} for local runs and
 * tests, {@link HttpUserManagementClient} for everything else - selected by
 * {@code wallet.auth.mode}. Nothing above this interface knows which one is in use.
 *
 * <p><b>The trade this design makes.</b> Calling out on every request means the identity
 * service's latency is added to ours and its downtime becomes our downtime. That is the cost of
 * instant revocation: a token that is disabled stops working on the very next call, which a
 * locally-verified JWT cannot promise until it expires. {@link CachingTokenValidator} buys most
 * of the latency back and bounds how long a revoked token keeps working.
 */
public interface UserManagementClient {

    /**
     * @return the user behind the token, or empty when the token is unknown, expired or revoked
     * @throws IdentityUnavailableException when user-management could not be reached or answered
     *         with a server error - which is NOT the same as "this token is invalid" and must
     *         not be turned into a 401
     */
    Optional<AuthenticatedUser> validate(String token);
}
