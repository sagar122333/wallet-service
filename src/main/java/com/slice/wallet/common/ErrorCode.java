package com.slice.wallet.common;

/**
 * Every failure this service can report, as a stable machine-readable code.
 *
 * <p>Deliberately carries <b>no HTTP status</b>. The domain and service layers throw these and
 * must not know they are being called over HTTP; the single mapping from code to status lives in
 * {@code GlobalExceptionHandler}, where it is an exhaustive switch the compiler checks. That is
 * the whole reason this is an enum and not a set of {@code String} constants: add a code and
 * forget the mapping, and the build fails instead of the endpoint quietly returning 422.
 *
 * <p>Clients branch on the code. Humans read the message. Never the other way round.
 */
public enum ErrorCode {

    // --- client sent something wrong -------------------------------------------------------
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    UNKNOWN_FIELD,
    INVALID_CURSOR,
    IDEMPOTENCY_KEY_REQUIRED,
    PAYLOAD_TOO_LARGE,

    // --- identity ---------------------------------------------------------------------------
    UNAUTHENTICATED,
    FORBIDDEN,
    /** We could not reach user-management to check the token. NOT the same as a bad token. */
    IDENTITY_UNAVAILABLE,

    // --- addressing -------------------------------------------------------------------------
    WALLET_NOT_FOUND,
    TRANSACTION_NOT_FOUND,
    TRANSFER_NOT_FOUND,
    NO_SUCH_ROUTE,
    METHOD_NOT_ALLOWED,

    // --- conflicts with current state -------------------------------------------------------
    IDEMPOTENCY_KEY_REUSED,
    REQUEST_IN_FLIGHT,
    ALREADY_REVERSED,
    WALLET_ALREADY_EXISTS,

    // --- business rules refused the request -------------------------------------------------
    INSUFFICIENT_FUNDS,
    CURRENCY_MISMATCH,
    SAME_WALLET,
    WALLET_NOT_ACTIVE,
    CANNOT_REVERSE_A_REVERSAL,
    REVERSE_TRANSFER_AS_TRANSFER,

    // --- ours -------------------------------------------------------------------------------
    CONCURRENT_UPDATE,
    INTERNAL_ERROR
}
