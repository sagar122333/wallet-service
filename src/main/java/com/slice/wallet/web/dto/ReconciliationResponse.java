package com.slice.wallet.web.dto;

import com.slice.wallet.service.LedgerQueryService;

/**
 * The operational check: does the cached balance still equal the fold over the ledger?
 *
 * <p>{@code inSync: false} means this service has a bug, not that the wallet is unusual.
 */
public record ReconciliationResponse(String walletId,
                                     long cachedBalanceMinor,
                                     long ledgerBalanceMinor,
                                     long entryCount,
                                     boolean inSync) {

    public static ReconciliationResponse from(LedgerQueryService.Reconciliation reconciliation) {
        return new ReconciliationResponse(
                reconciliation.walletId(),
                reconciliation.cachedBalanceMinor(),
                reconciliation.ledgerBalanceMinor(),
                reconciliation.entryCount(),
                reconciliation.inSync());
    }
}
