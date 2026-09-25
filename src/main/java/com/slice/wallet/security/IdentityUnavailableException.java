package com.slice.wallet.security;

/**
 * User-management could not be reached, or failed.
 *
 * <p>A distinct type because the difference matters enormously: "your token is invalid" is a
 * <b>401</b> and the client should re-authenticate, while "we could not check your token" is a
 * <b>503</b> and the client should retry. Collapsing the two would log every caller out during
 * an identity-service outage - and they would all stampede the login flow, which is exactly when
 * that service can least afford it.
 */
public class IdentityUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdentityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
