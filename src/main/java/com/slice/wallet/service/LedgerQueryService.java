package com.slice.wallet.service;

import com.slice.wallet.domain.EntryType;
import com.slice.wallet.domain.LedgerEntry;
import com.slice.wallet.persistence.LedgerEntryRepository;
import com.slice.wallet.persistence.LedgerEntryRow;
import com.slice.wallet.persistence.WalletRow;
import com.slice.wallet.security.Caller;
import com.slice.wallet.security.Scopes;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Read-only views over the ledger: history pages and the reconciliation check. */
@Service
public class LedgerQueryService {

    /** One page. {@code hasMore} is what decides whether a next cursor is issued. */
    public record HistoryPage(List<LedgerEntry> entries, boolean hasMore) {
    }

    /**
     * The invariant, measured: the cached balance against the fold over the ledger.
     *
     * <p>If these ever disagree the service has a bug, and {@code balanceAfterMinor} on that
     * wallet's entries identifies the one they diverged at.
     */
    public record Reconciliation(String walletId, long cachedBalanceMinor, long ledgerBalanceMinor,
                                 long entryCount, boolean inSync) {
    }

    private final WalletService walletService;
    private final LedgerEntryRepository ledger;

    public LedgerQueryService(WalletService walletService, LedgerEntryRepository ledger) {
        this.walletService = walletService;
        this.ledger = ledger;
    }

    /**
     * A page of history, newest first.
     *
     * <p>Asks for {@code limit + 1} rows: the extra one answers "is there another page?" without
     * a second {@code COUNT} query, and is trimmed before it reaches the client.
     */
    @Transactional(readOnly = true)
    public HistoryPage history(Caller caller, String walletId, Instant beforeAt, String beforeId,
                               int limit) {
        caller.requireScope(Scopes.READ);
        WalletRow row = walletService.requireVisible(walletId, caller);

        List<LedgerEntry> rows = ledger
                .findPage(row.walletId(), beforeAt, beforeId, PageRequest.of(0, limit + 1))
                .stream()
                .map(LedgerEntryRow::toDomain)
                .toList();

        boolean hasMore = rows.size() > limit;
        return new HistoryPage(hasMore ? rows.subList(0, limit) : rows, hasMore);
    }

    @Transactional(readOnly = true)
    public Reconciliation reconcile(Caller caller, String walletId) {
        // Admin-only: it is an operational check, and the entry count plus the raw minor units
        // are more than a customer needs.
        caller.requireScope(Scopes.ADMIN);
        WalletRow row = walletService.requireVisible(walletId, caller);

        long ledgerBalance = ledger.sumSignedMinor(row.walletId(), EntryType.CREDIT);
        return new Reconciliation(row.walletId(), row.balanceMinor(), ledgerBalance,
                ledger.countByWalletId(row.walletId()), row.balanceMinor() == ledgerBalance);
    }
}
