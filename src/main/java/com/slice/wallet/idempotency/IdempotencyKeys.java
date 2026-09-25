package com.slice.wallet.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** How an idempotency key is namespaced, and how a request is fingerprinted. */
public final class IdempotencyKeys {

    /** scope_key is VARCHAR(191); this leaves room for a 64-char user id and the separator. */
    public static final int MAX_KEY_LENGTH = 120;

    private IdempotencyKeys() {
    }

    /**
     * Namespaces the caller's key to the caller.
     *
     * <p>Two things depend on this. Two users must be able to send the key {@code "1"} without
     * colliding - a shared namespace would mean one user's retry silently returns the other's
     * result. And no user must be able to replay someone else's response by guessing a key.
     */
    public static String scopeKey(String userId, String idempotencyKey) {
        return userId + '|' + idempotencyKey;
    }

    /**
     * A hash of the parts of the request the key promises are unchanged.
     *
     * <p>A key is a promise that this is the <i>same</i> request. If the body differs under the
     * same key it is a client bug, and replaying the first response would silently swallow a
     * second, real payment - so that is a 409 rather than a replay.
     */
    public static String fingerprint(String method, String path, String body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String canonical = method + '\n' + path + '\n' + (body == null ? "" : body);
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
