package com.slice.wallet.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Stand-in for user-management, active when {@code wallet.auth.mode=stub}.
 *
 * <p>Every token is accepted. The token string itself becomes the user id, so you can act as
 * different people from curl and from tests without an identity service running:
 *
 * <pre>
 *   Authorization: Bearer user-asha
 *       -> user "user-asha" with every scope
 *
 *   Authorization: Bearer user-asha|wallet:read
 *       -> user "user-asha" with ONLY wallet:read, so a write returns 403
 * </pre>
 *
 * <p>The pipe convention exists purely so a test can construct an exact scope set - the real
 * client ignores the token's content entirely and asks user-management what it means. This class
 * is what you delete, or leave unreachable, the moment a real identity service exists; it is
 * guarded by a property rather than a profile so that the switch is one line of configuration
 * and needs no rebuild.
 */
@Component
@ConditionalOnProperty(name = "wallet.auth.mode", havingValue = "stub", matchIfMissing = true)
public class StubUserManagementClient implements UserManagementClient {

    private static final Logger log = LoggerFactory.getLogger(StubUserManagementClient.class);

    private static final Set<String> ALL_SCOPES =
            Set.of(Scopes.READ, Scopes.WRITE, Scopes.REVERSE, Scopes.ADMIN);

    public StubUserManagementClient() {
        // Loud, and at WARN: a service that accepts every token should never start this way
        // anywhere that matters without somebody noticing in the first ten lines of the log.
        log.warn("AUTH IS STUBBED (wallet.auth.mode=stub) - every bearer token is accepted. "
                + "Set wallet.auth.mode=remote to validate against user-management.");
    }

    @Override
    public Optional<AuthenticatedUser> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int pipe = token.indexOf('|');
        if (pipe < 0) {
            return Optional.of(new AuthenticatedUser(token.trim(), ALL_SCOPES));
        }
        String userId = token.substring(0, pipe).trim();
        Set<String> scopes = new LinkedHashSet<>(
                Arrays.asList(token.substring(pipe + 1).split("\\s*,\\s*")));
        scopes.removeIf(String::isBlank);
        return userId.isBlank() ? Optional.empty() : Optional.of(new AuthenticatedUser(userId, scopes));
    }
}
