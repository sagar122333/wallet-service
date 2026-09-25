package com.slice.wallet.web.paging;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.WalletException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorTest {

    @Test
    @DisplayName("a cursor round-trips exactly, microseconds included")
    void roundTrips() {
        Cursor original = new Cursor(Instant.parse("2026-01-01T10:15:30.123456Z"), "entry-42");

        Cursor decoded = Cursor.decode(original.encode());

        assertEquals(original, decoded, "losing sub-second precision would skip or repeat rows");
    }

    @Test
    @DisplayName("an absent cursor is null, not an error")
    void absentIsNull() {
        assertNull(Cursor.decode(null));
        assertNull(Cursor.decode(""));
        assertNull(Cursor.decode("   "));
    }

    @Test
    @DisplayName("a cursor we did not issue is a 400, never a silently empty page")
    void forgedCursorIsRejected() {
        WalletException e = assertThrows(WalletException.class, () -> Cursor.decode("not-a-cursor"));
        assertEquals(ErrorCode.INVALID_CURSOR, e.code());

        assertThrows(WalletException.class, () -> Cursor.decode("YWJjZGVm"),
                "valid base64 that is not a cursor is still a bad cursor");
    }

    @Test
    @DisplayName("the encoded form is url-safe and unpadded")
    void encodingIsUrlSafe() {
        String encoded = new Cursor(Instant.parse("2026-01-01T10:15:30.123456Z"), "entry-42").encode();

        assertTrue(encoded.chars().noneMatch(c -> c == '+' || c == '/' || c == '='),
                "a cursor goes in a query string, so it must not need escaping");
    }
}
