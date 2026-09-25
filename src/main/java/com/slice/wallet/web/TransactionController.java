package com.slice.wallet.web;

import com.slice.wallet.idempotency.IdempotencyService;
import com.slice.wallet.security.Caller;
import com.slice.wallet.security.CurrentCaller;
import com.slice.wallet.security.Scopes;
import com.slice.wallet.service.ReversalService;
import com.slice.wallet.web.dto.ReversalRequest;
import com.slice.wallet.web.dto.ReversalResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reversing a standalone transaction.
 *
 * <p>{@code wallet:reverse} is an <b>operator</b> scope, not a customer one: a customer reversing
 * their own spend is a refund they granted themselves. This is the endpoint that makes the API's
 * 403 mean something.
 */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    private final ReversalService reversalService;
    private final IdempotentExecutor idempotently;

    public TransactionController(ReversalService reversalService, IdempotentExecutor idempotently) {
        this.reversalService = reversalService;
        this.idempotently = idempotently;
    }

    @PostMapping("/{entryId}/reversals")
    @PreAuthorize(Scopes.HAS_REVERSE)
    public ResponseEntity<ReversalResponse> reverse(
            @CurrentCaller Caller caller,
            @PathVariable String entryId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReversalRequest request,
            HttpServletRequest httpRequest) {

        return idempotently.execute(caller, idempotencyKey, httpRequest, request,
                ReversalResponse.class,
                null,
                () -> {
                    ReversalService.ReversalResult result = reversalService.reverseEntry(
                            caller, entryId, request.reason(), idempotencyKey);
                    return new IdempotencyService.Attempt<>(201, ReversalResponse.from(result), entryId);
                });
    }
}
