package com.slice.wallet.common;

/**
 * The one exception type this application throws for anything the caller should be told about.
 *
 * <p>One type, not a hierarchy of thirty. The thing that varies between failures is the
 * {@link ErrorCode}, which is data, so it belongs in a field rather than in a class name. A
 * subclass per failure would force {@code GlobalExceptionHandler} to grow an
 * {@code @ExceptionHandler} method per failure, and every one of them would do the same thing.
 *
 * <p>Carries no HTTP status - see {@link ErrorCode}.
 */
public class WalletException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public WalletException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public WalletException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    // Factories for the failures raised from more than one place, so the message stays
    // identical wherever it is thrown. A 404 for "not yours" MUST read exactly like a 404 for
    // "does not exist" - see WalletService.requireVisible.
    public static WalletException walletNotFound() {
        return new WalletException(ErrorCode.WALLET_NOT_FOUND, "no such wallet");
    }

    public static WalletException insufficientFunds(String detail) {
        return new WalletException(ErrorCode.INSUFFICIENT_FUNDS, detail);
    }

    public static WalletException forbidden(String detail) {
        return new WalletException(ErrorCode.FORBIDDEN, detail);
    }
}
