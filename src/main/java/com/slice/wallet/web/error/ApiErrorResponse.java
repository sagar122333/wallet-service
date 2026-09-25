package com.slice.wallet.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slice.wallet.common.ErrorCode;

import java.util.List;

/**
 * The one error shape this API ever returns.
 *
 * <p>Every failure - validation, auth, business rule, our own bug - comes back as
 * {@code {"error": {"code", "message", "requestId", "details"}}}, so a client writes one error
 * handler instead of one per endpoint. The {@code code} is the contract and clients branch on it;
 * the {@code message} is for humans and may change without notice.
 *
 * <p>{@code requestId} is in the body as well as the {@code X-Request-Id} header because it is
 * what a support engineer pastes into a log search when a user forwards a screenshot.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(ApiError error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String code, String message, String requestId, List<FieldIssue> details) {
    }

    /** Per-field detail for a validation failure. Null for every other kind of error. */
    public record FieldIssue(String field, String message) {
    }

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId) {
        return new ApiErrorResponse(new ApiError(code.name(), message, requestId, null));
    }

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId,
                                      List<FieldIssue> details) {
        return new ApiErrorResponse(new ApiError(code.name(), message, requestId,
                details == null || details.isEmpty() ? null : details));
    }
}
