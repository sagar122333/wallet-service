package com.slice.wallet.web.paging;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.WalletException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * The opaque pagination cursor: a composite sort key, base64url encoded.
 *
 * <p><b>Why a cursor and not offset/limit.</b> A ledger is append-only and read newest-first, so
 * with {@code OFFSET 20} an entry that lands between page 1 and page 2 shifts every row down and
 * the client sees one entry twice and misses another. A cursor names the last row the client
 * saw, so the next page is "strictly older than this", which is stable under concurrent writes.
 * Offset is also O(offset) in every SQL engine; keyset paging uses the index.
 *
 * <p><b>Why the id as well as the timestamp.</b> Timestamps collide under load. Without a
 * tie-breaker, a page boundary landing inside a group of same-instant entries silently drops or
 * repeats them.
 *
 * <p><b>Why opaque.</b> Base64 is not security - it is a contract. A client that cannot read the
 * cursor cannot depend on its shape, which leaves us free to change the sort key later. A
 * readable {@code ?offset=40} becomes part of the public API the moment someone hand-writes one.
 */
public record Cursor(Instant at, String id) {

    private static final char SEPARATOR = '|';

    public String encode() {
        String raw = at.toString() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Null for an absent cursor. A malformed cursor is a 400, never a silently empty page. */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf(SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("no separator");
            }
            return new Cursor(Instant.parse(raw.substring(0, separator)), raw.substring(separator + 1));
        } catch (RuntimeException malformed) {
            throw new WalletException(ErrorCode.INVALID_CURSOR, "cursor is not one we issued");
        }
    }
}
