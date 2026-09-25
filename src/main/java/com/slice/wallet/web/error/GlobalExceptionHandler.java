package com.slice.wallet.web.error;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.WalletException;
import com.slice.wallet.web.RequestIdFilter;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * The one place a throwable becomes an HTTP response.
 *
 * <p>Controllers throw; this translates. The value is that every error in the API has the same
 * body shape, so a client writes one error handler, and no controller ever builds an error by
 * hand and gets the shape subtly wrong.
 *
 * <p><b>The status mapping is an exhaustive switch.</b> Add a constant to {@link ErrorCode} and
 * forget to map it and the <i>build</i> fails - which is the entire reason the codes are an enum
 * and the mapping is a switch expression with no {@code default}. The alternative, matching on
 * message strings with a fallback, turns a forgotten case into an endpoint that silently returns
 * the wrong status.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The status each failure maps to.
     *
     * <p>Three distinctions worth being able to defend:
     * <ul>
     *   <li><b>400 vs 422.</b> 400 means the client sent nonsense and should fix the request.
     *       422 means the request was fine and the world said no - insufficient funds, a
     *       currency that does not match. Clients retry those differently.</li>
     *   <li><b>401 vs 403.</b> 401 is "we do not know you", and refreshing a token can fix it.
     *       403 is "we know you and you may not", and retrying never will.</li>
     *   <li><b>404 vs 403 for someone else's wallet.</b> 404, deliberately: a 403 confirms the
     *       id is real and turns the endpoint into a wallet-id oracle.</li>
     * </ul>
     */
    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED, MALFORMED_REQUEST, UNKNOWN_FIELD, INVALID_CURSOR,
                 IDEMPOTENCY_KEY_REQUIRED -> HttpStatus.BAD_REQUEST;

            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;

            case WALLET_NOT_FOUND, TRANSACTION_NOT_FOUND, TRANSFER_NOT_FOUND,
                 NO_SUCH_ROUTE -> HttpStatus.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;

            case IDEMPOTENCY_KEY_REUSED, REQUEST_IN_FLIGHT, ALREADY_REVERSED,
                 WALLET_ALREADY_EXISTS, CONCURRENT_UPDATE -> HttpStatus.CONFLICT;

            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;

            case INSUFFICIENT_FUNDS, CURRENCY_MISMATCH, SAME_WALLET, WALLET_NOT_ACTIVE,
                 CANNOT_REVERSE_A_REVERSAL, REVERSE_TRANSFER_AS_TRANSFER ->
                    HttpStatus.UNPROCESSABLE_ENTITY;

            // We do not know whether the caller is who they say they are, so we cannot answer
            // 401 ("your token is bad") - that would log every customer out during an identity
            // outage and then stampede the login flow.
            case IDENTITY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;

            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    @ExceptionHandler(WalletException.class)
    public ResponseEntity<ApiErrorResponse> handleWalletException(WalletException e) {
        HttpStatus status = statusFor(e.code());
        if (status.is5xxServerError()) {
            log.error("[{}] {}", e.code(), e.getMessage(), e);
        } else {
            log.debug("[{}] {}", e.code(), e.getMessage());
        }
        return body(status, e.code(), e.getMessage());
    }

    /** {@code @Valid} on a request body. Every offending field is named, not just the first. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
        List<ApiErrorResponse.FieldIssue> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldIssue(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED, "the request body is not valid",
                RequestIdFilter.currentRequestId(), details));
    }

    /** {@code @Min}/{@code @Max} on query parameters, via {@code @Validated} on the controller. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidParams(ConstraintViolationException e) {
        List<ApiErrorResponse.FieldIssue> details = e.getConstraintViolations().stream()
                .map(violation -> new ApiErrorResponse.FieldIssue(
                        String.valueOf(violation.getPropertyPath()), violation.getMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED, "a request parameter is not valid",
                RequestIdFilter.currentRequestId(), details));
    }

    /**
     * Unparseable JSON, a wrong type, or an unknown field.
     *
     * <p>Unknown fields are rejected rather than ignored
     * ({@code spring.jackson.deserialization.fail-on-unknown-properties=true}). Silently
     * dropping a field the client believes matters is how a client ships a bug it cannot see -
     * a misspelled {@code amountMinorr} would otherwise look like a successful zero-amount call.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof UnrecognizedPropertyException unknown) {
            return body(HttpStatus.BAD_REQUEST, ErrorCode.UNKNOWN_FIELD,
                    "unexpected field '" + unknown.getPropertyName() + "'");
        }
        return body(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "the request body could not be read as JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "parameter '" + e.getName() + "' has the wrong type");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "the " + e.getHeaderName() + " header is required");
    }

    /** From {@code @PreAuthorize}. Kept here so a 403 looks like every other error. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                "this token is not allowed to perform that operation");
    }

    /** 405, not 404: the path is right and the client's bug is the verb. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleWrongMethod(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED,
                e.getMethod() + " is not supported on this path");
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNoRoute(Exception e) {
        return body(HttpStatus.NOT_FOUND, ErrorCode.NO_SUCH_ROUTE, "no such endpoint");
    }

    /**
     * A constraint the application layer did not anticipate.
     *
     * <p>409 rather than 500 because a unique constraint firing is almost always a genuine
     * conflict with the current state - a duplicate wallet, a race the fast path missed. The
     * detail is logged and not returned: constraint names are internal.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraint(DataIntegrityViolationException e) {
        log.warn("constraint violation reached the boundary", e);
        return body(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_UPDATE,
                "the request conflicts with the current state of the resource");
    }

    /** The {@code @Version} column firing: someone changed the row between read and write. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("optimistic lock failure - a write path may be missing its row lock", e);
        return body(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_UPDATE,
                "the resource was modified concurrently; retry the request");
    }

    /**
     * A deadlock or lock-wait timeout that survived the service's retries.
     *
     * <p>503 with {@code Retry-After}, not 500: nothing is wrong with the request, the system is
     * simply contended, and the honest instruction to the client is "try again shortly".
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleLockFailure(PessimisticLockingFailureException e) {
        log.warn("lock acquisition failed after retries", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(ApiErrorResponse.of(ErrorCode.CONCURRENT_UPDATE,
                        "the wallet is busy; retry the request",
                        RequestIdFilter.currentRequestId()));
    }

    /**
     * Anything else is our bug.
     *
     * <p>Log the detail, return none of it. An internal message is an information leak and tells
     * the client nothing actionable - but the requestId in the body is what lets a support
     * engineer find this exact stack trace from a screenshot.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "an unexpected error occurred");
    }

    private ResponseEntity<ApiErrorResponse> body(HttpStatus status, ErrorCode code, String message) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(code, message, RequestIdFilter.currentRequestId()));
    }
}
