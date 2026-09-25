package com.slice.wallet.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authentication step, on its own - no Spring context, no database, no HTTP server.
 *
 * <p>This is where the cases the integration suite cannot reach are covered: a token
 * user-management rejects, and user-management being down. Under {@code wallet.auth.mode=stub}
 * every non-blank token is deliberately accepted, so neither can be provoked over HTTP.
 *
 * <p>No mocking framework: the collaborator is an interface with one method, so a lambda is a
 * better test double than a mock - it cannot drift out of date with the interface, and it reads
 * as the scenario it represents.
 */
class BearerAuthenticationFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** TTL zero disables caching, so each test sees exactly the answers it sets up. */
    private BearerAuthenticationFilter filterFor(UserManagementClient client) {
        return new BearerAuthenticationFilter(
                new CachingTokenValidator(client, Clock.systemUTC(), Duration.ZERO), objectMapper);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("no Authorization header: 401, and the chain is never invoked")
    void missingHeaderStopsTheRequest() throws Exception {
        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/wallets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterFor(token -> Optional.empty()).doFilterInternal(request, response,
                (req, res) -> chainCalls.incrementAndGet());

        assertEquals(401, response.getStatus());
        assertEquals(0, chainCalls.get(), "a controller must never see an unauthenticated request");
        assertTrue(response.getContentAsString().contains("UNAUTHENTICATED"),
                "and the body is this API's normal error envelope");
    }

    @Test
    @DisplayName("a token user-management rejects: 401")
    void unknownTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/wallets");
        request.addHeader("Authorization", "Bearer revoked-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // user-management answered definitively: this token is not good.
        filterFor(token -> Optional.empty()).doFilterInternal(request, response, (req, res) -> { });

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("invalid or expired token"),
                "unknown, expired and revoked all read identically - an error body must not tell "
                        + "an attacker which half of the guess was right");
    }

    @Test
    @DisplayName("user-management unreachable: 503 with Retry-After, NOT 401")
    void identityOutageIsNotALogout() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/wallets");
        request.addHeader("Authorization", "Bearer perfectly-good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UserManagementClient down = token -> {
            throw new IdentityUnavailableException("connection refused", new RuntimeException());
        };
        filterFor(down).doFilterInternal(request, response, (req, res) -> { });

        assertEquals(503, response.getStatus(),
                "401 here would log every customer out at once and then stampede the login flow");
        assertEquals("2", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("IDENTITY_UNAVAILABLE"));
    }

    @Test
    @DisplayName("a valid token puts a Caller, with SCOPE_ authorities, into the context")
    void validTokenPopulatesTheContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/wallets");
        request.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UserManagementClient client = token -> Optional.of(
                new AuthenticatedUser("user-asha", Set.of(Scopes.READ, Scopes.WRITE)));

        AtomicReference<Object> principalDuringRequest = new AtomicReference<>();
        AtomicReference<Object> authoritiesDuringRequest = new AtomicReference<>();

        filterFor(client).doFilterInternal(request, response, (req, res) -> {
            // Captured INSIDE the chain: this is exactly what a controller and @PreAuthorize see.
            principalDuringRequest.set(
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            authoritiesDuringRequest.set(
                    SecurityContextHolder.getContext().getAuthentication().getAuthorities().toString());
        });

        assertNotNull(principalDuringRequest.get(), "the chain must have run");
        Caller caller = (Caller) principalDuringRequest.get();
        assertEquals("user-asha", caller.userId());
        assertTrue(caller.hasScope(Scopes.WRITE));
        assertTrue(String.valueOf(authoritiesDuringRequest.get()).contains("SCOPE_wallet:write"),
                "@PreAuthorize matches on the SCOPE_ prefix, so the mapping has to be exact");

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "and the context is cleared afterwards - worker threads are pooled, so a "
                        + "leftover principal would authenticate the NEXT request as this user");
    }

    @Test
    @DisplayName("the health probe skips authentication entirely")
    void healthProbeIsNotFiltered() {
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletRequest api = new MockHttpServletRequest("GET", "/v1/wallets");
        BearerAuthenticationFilter filter = filterFor(token -> Optional.empty());

        assertTrue(filter.shouldNotFilter(health),
                "a load balancer has no token, and a probe that can fail for an auth reason lies");
        assertTrue(!filter.shouldNotFilter(api));
    }

    @Test
    @DisplayName("inside the TTL, a repeated token does not call user-management again")
    void theCacheAbsorbsRepeatedCalls() {
        AtomicInteger calls = new AtomicInteger();
        UserManagementClient counting = token -> {
            calls.incrementAndGet();
            return Optional.of(new AuthenticatedUser("user-asha", Set.of(Scopes.READ)));
        };
        CachingTokenValidator validator =
                new CachingTokenValidator(counting, Clock.systemUTC(), Duration.ofSeconds(60));

        for (int i = 0; i < 20; i++) {
            assertTrue(validator.validate("same-token").isPresent());
        }

        assertEquals(1, calls.get(),
                "twenty requests, one introspection call - otherwise their p99 becomes our p99");

        validator.validate("a-different-token");
        assertEquals(2, calls.get(), "a different token is a different cache entry");
        assertEquals(2, validator.size());

        validator.invalidate("same-token");
        validator.validate("same-token");
        assertEquals(3, calls.get(),
                "and an explicit invalidation forces a fresh check - the revocation hook");
    }
}
