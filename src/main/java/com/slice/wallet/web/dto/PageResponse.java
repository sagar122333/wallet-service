package com.slice.wallet.web.dto;

import java.util.List;

/**
 * The paging envelope.
 *
 * <p>{@code nextCursor} is always present as a key and is {@code null} at the end, so a client's
 * paging loop tests one value rather than inferring the end from a short page. It is issued only
 * when another page genuinely exists, which saves the client one request per listing.
 */
public record PageResponse<T>(List<T> items, String nextCursor) {
}
