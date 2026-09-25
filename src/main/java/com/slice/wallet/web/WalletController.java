package com.slice.wallet.web;

import com.slice.wallet.domain.LedgerEntry;
import com.slice.wallet.domain.Wallet;
import com.slice.wallet.idempotency.IdempotencyService;
import com.slice.wallet.security.Caller;
import com.slice.wallet.security.CurrentCaller;
import com.slice.wallet.security.Scopes;
import com.slice.wallet.service.LedgerQueryService;
import com.slice.wallet.service.WalletService;
import com.slice.wallet.web.dto.AmountRequest;
import com.slice.wallet.web.dto.CreateWalletRequest;
import com.slice.wallet.web.dto.PageResponse;
import com.slice.wallet.web.dto.ReconciliationResponse;
import com.slice.wallet.web.dto.TransactionResponse;
import com.slice.wallet.web.dto.WalletResponse;
import com.slice.wallet.web.paging.Cursor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Wallets, and the two single-wallet money movements.
 *
 * <p>Every method here does exactly three things: read validated input, call one service method,
 * render the result. No branching on business rules, no arithmetic, no locking - anything of
 * that kind in a controller is in the wrong layer and cannot be tested without a servlet.
 *
 * <p><b>Resource shape.</b> A movement is not a verb on a wallet, it is a resource created
 * underneath it: {@code POST /v1/wallets/{id}/credits}, not {@code POST /wallet/doCredit}. That
 * is what makes 201 plus a {@code Location} header meaningful, and what makes the resulting
 * transaction addressable afterwards for a reversal.
 *
 * <p>{@code @PreAuthorize} here is the coarse gate; the service re-checks the same scope so a
 * future non-HTTP entry point cannot bypass it.
 */
@RestController
@RequestMapping("/v1/wallets")
@Validated
public class WalletController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final WalletService walletService;
    private final LedgerQueryService ledgerQueries;
    private final IdempotentExecutor idempotently;

    public WalletController(WalletService walletService, LedgerQueryService ledgerQueries,
                            IdempotentExecutor idempotently) {
        this.walletService = walletService;
        this.ledgerQueries = ledgerQueries;
        this.idempotently = idempotently;
    }

    @PostMapping
    @PreAuthorize(Scopes.HAS_WRITE)
    public ResponseEntity<WalletResponse> create(
            @CurrentCaller Caller caller,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateWalletRequest request,
            HttpServletRequest httpRequest) {

        return idempotently.execute(caller, idempotencyKey, httpRequest, request,
                WalletResponse.class,
                body -> "/v1/wallets/" + body.walletId(),
                () -> {
                    Wallet wallet = walletService.create(caller, request.userId(), request.currency());
                    return new IdempotencyService.Attempt<>(201, WalletResponse.from(wallet), wallet.walletId());
                });
    }

    @GetMapping
    @PreAuthorize(Scopes.HAS_READ)
    public PageResponse<WalletResponse> listOwn(@CurrentCaller Caller caller) {
        List<WalletResponse> items = walletService.listOwn(caller).stream()
                .map(WalletResponse::from)
                .toList();
        // A user has a handful of wallets - one per currency - so this is not paged. The
        // envelope still matches every other listing so clients do not special-case it.
        return new PageResponse<>(items, null);
    }

    @GetMapping("/{walletId}")
    @PreAuthorize(Scopes.HAS_READ)
    public WalletResponse get(@CurrentCaller Caller caller, @PathVariable String walletId) {
        return WalletResponse.from(walletService.get(walletId, caller));
    }

    @PostMapping("/{walletId}/credits")
    @PreAuthorize(Scopes.HAS_WRITE)
    public ResponseEntity<TransactionResponse> credit(
            @CurrentCaller Caller caller,
            @PathVariable String walletId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AmountRequest request,
            HttpServletRequest httpRequest) {

        return idempotently.execute(caller, idempotencyKey, httpRequest, request,
                TransactionResponse.class,
                body -> "/v1/transactions/" + body.transactionId(),
                () -> {
                    // The Idempotency-Key doubles as the ledger's request_id, so the transport
                    // dedup and the UNIQUE (wallet_id, request_id) constraint are keyed on the
                    // same value. Two independent mechanisms, one key.
                    LedgerEntry entry =
                            walletService.credit(walletId, caller, request.toMoney(), idempotencyKey);
                    return new IdempotencyService.Attempt<>(201, TransactionResponse.from(entry), entry.entryId());
                });
    }

    @PostMapping("/{walletId}/debits")
    @PreAuthorize(Scopes.HAS_WRITE)
    public ResponseEntity<TransactionResponse> debit(
            @CurrentCaller Caller caller,
            @PathVariable String walletId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AmountRequest request,
            HttpServletRequest httpRequest) {

        return idempotently.execute(caller, idempotencyKey, httpRequest, request,
                TransactionResponse.class,
                body -> "/v1/transactions/" + body.transactionId(),
                () -> {
                    LedgerEntry entry =
                            walletService.debit(walletId, caller, request.toMoney(), idempotencyKey);
                    return new IdempotencyService.Attempt<>(201, TransactionResponse.from(entry), entry.entryId());
                });
    }

    @GetMapping("/{walletId}/transactions")
    @PreAuthorize(Scopes.HAS_READ)
    public PageResponse<TransactionResponse> history(
            @CurrentCaller Caller caller,
            @PathVariable String walletId,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = 100, message = "limit must be at most 100") int limit,
            @RequestParam(required = false) String cursor) {

        Cursor decoded = Cursor.decode(cursor);
        LedgerQueryService.HistoryPage page = ledgerQueries.history(caller, walletId,
                decoded == null ? null : decoded.at(),
                decoded == null ? null : decoded.id(),
                limit);

        String nextCursor = null;
        if (page.hasMore() && !page.entries().isEmpty()) {
            LedgerEntry last = page.entries().get(page.entries().size() - 1);
            nextCursor = new Cursor(last.at(), last.entryId()).encode();
        }
        return new PageResponse<>(page.entries().stream().map(TransactionResponse::from).toList(), nextCursor);
    }

    /** Operational check. Admin only - see LedgerQueryService#reconcile. */
    @GetMapping("/{walletId}/reconciliation")
    @PreAuthorize(Scopes.HAS_ADMIN)
    public ReconciliationResponse reconcile(@CurrentCaller Caller caller, @PathVariable String walletId) {
        return ReconciliationResponse.from(ledgerQueries.reconcile(caller, walletId));
    }
}
