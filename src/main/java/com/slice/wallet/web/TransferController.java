package com.slice.wallet.web;

import com.slice.wallet.idempotency.IdempotencyService;
import com.slice.wallet.security.Caller;
import com.slice.wallet.security.CurrentCaller;
import com.slice.wallet.security.Scopes;
import com.slice.wallet.service.ReversalService;
import com.slice.wallet.service.TransferService;
import com.slice.wallet.web.dto.ReversalRequest;
import com.slice.wallet.web.dto.ReversalResponse;
import com.slice.wallet.web.dto.TransferRequest;
import com.slice.wallet.web.dto.TransferResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfers: the one operation that touches two wallets.
 *
 * <p>It gets its own URL space rather than living under one of the wallets, because it belongs to
 * neither of them. {@code POST /v1/wallets/{id}/transfers} would imply the source owns the
 * transfer, and then the recipient's side has no address.
 */
@RestController
@RequestMapping("/v1/transfers")
public class TransferController {

    private final TransferService transferService;
    private final ReversalService reversalService;
    private final IdempotentExecutor idempotently;

    public TransferController(TransferService transferService, ReversalService reversalService,
                              IdempotentExecutor idempotently) {
        this.transferService = transferService;
        this.reversalService = reversalService;
        this.idempotently = idempotently;
    }

    @PostMapping
    @PreAuthorize(Scopes.HAS_WRITE)
    public ResponseEntity<TransferResponse> transfer(
            @CurrentCaller Caller caller,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            HttpServletRequest httpRequest) {

        return idempotently.execute(caller, idempotencyKey, httpRequest, request,
                TransferResponse.class,
                body -> "/v1/transfers/" + body.transferId(),
                () -> {
                    TransferService.TransferResult result = transferService.transfer(
                            caller, request.fromWalletId(), request.toWalletId(),
                            request.toMoney(), idempotencyKey);
                    return new IdempotencyService.Attempt<>(201, TransferResponse.from(result),
                            result.transferId());
                });
    }

    @GetMapping("/{transferId}")
    @PreAuthorize(Scopes.HAS_READ)
    public TransferResponse get(@CurrentCaller Caller caller, @PathVariable String transferId) {
        return TransferResponse.from(transferService.get(caller, transferId));
    }

    /**
     * Reverses BOTH legs, together.
     *
     * <p>A separate route from {@code /v1/transactions/{entryId}/reversals} on purpose: an entry
     * id and a transfer id are two different namespaces, and overloading one path parameter with
     * both is how one leg of a transfer ends up reversed alone - which leaves every individual
     * wallet's ledger consistent while creating money overall.
     */
    @PostMapping("/{transferId}/reversals")
    @PreAuthorize(Scopes.HAS_REVERSE)
    public ResponseEntity<ReversalResponse> reverse(
            @CurrentCaller Caller caller,
            @PathVariable String transferId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReversalRequest request,
            HttpServletRequest httpRequest) {

        return idempotently.execute(caller, idempotencyKey, httpRequest, request,
                ReversalResponse.class,
                null,
                () -> {
                    ReversalService.ReversalResult result = reversalService.reverseTransfer(
                            caller, transferId, request.reason(), idempotencyKey);
                    return new IdempotencyService.Attempt<>(201, ReversalResponse.from(result), transferId);
                });
    }
}
