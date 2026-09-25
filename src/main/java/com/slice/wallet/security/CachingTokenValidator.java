package com.slice.wallet.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A short-lived cache in front of {@link UserManagementClient}.
 *
 * <p>This is what the filter actually depends on, so the network call is an implementation
 * detail one layer further down.
 *
 * <p><b>Why it exists.</b> Without it, a client that sends twenty requests in a burst causes
 * twenty introspection calls, and user-management's p99 becomes our p99 on every single
 * endpoint. Sixty seconds of caching removes almost all of that.
 *
 * <p><b>What it costs.</b> A revoked token keeps working for up to the TTL. That is the honest
 * trade and the number is configurable for exactly that reason: it is a security decision, not a
 * performance one. If a token must die instantly, the TTL goes to zero and the latency comes
 * back. (The fuller answer is a revocation event from user-management that evicts by user id -
 * not built here; see the README.)
 *
 * <p><b>Why the key is a hash.</b> The cache holds live credentials otherwise. Keying by
 * SHA-256 means a heap dump, a debugger session or an accidental {@code toString()} never
 * exposes a usable token.
 *
 * <p>Negative answers are cached too, briefly: without that, a flood of invalid tokens is a
 * free amplification attack against user-management.
 */
@Component
public class CachingTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(CachingTokenValidator.class);
    private static final int MAX_ENTRIES = 10_000;

    private record CacheEntry(Optional<AuthenticatedUser> result, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final UserManagementClient client;
    private final Clock clock;
    private final Duration ttl;

    public CachingTokenValidator(UserManagementClient client, Clock clock,
                                 @Value("${wallet.auth.cache-ttl:PT60S}") Duration ttl) {
        this.client = client;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Optional<AuthenticatedUser> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (ttl.isZero() || ttl.isNegative()) {
            return client.validate(token);     // caching explicitly disabled
        }

        String key = fingerprint(token);
        Instant now = clock.instant();

        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.result();
        }

        // Deliberately NOT computeIfAbsent: that holds a bin lock for the duration of the
        // mapping function, so one slow HTTP call would block every other thread hashing to the
        // same bin. A duplicate call under a race is far cheaper than that.
        Optional<AuthenticatedUser> fresh = client.validate(token);
        evictIfCrowded(now);
        cache.put(key, new CacheEntry(fresh, now.plus(ttl)));
        return fresh;
    }

    /** Drops this token immediately - for a logout or revocation hook. */
    public void invalidate(String token) {
        cache.remove(fingerprint(token));
    }

    public int size() {
        return cache.size();
    }

    /**
     * Bounded memory. Sweeping only when crowded keeps the common path free of scanning; in a
     * larger system this whole class is a Caffeine cache with a size bound and a TTL.
     */
    private void evictIfCrowded(Instant now) {
        if (cache.size() < MAX_ENTRIES) {
            return;
        }
        int before = cache.size();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (cache.size() >= MAX_ENTRIES) {
            // Still full of live entries: drop everything rather than grow without bound. The
            // cost is a burst of introspection calls, which is survivable; unbounded growth is
            // not.
            cache.clear();
        }
        log.debug("token cache swept from {} to {} entries", before, cache.size());
    }

    private static String fingerprint(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
