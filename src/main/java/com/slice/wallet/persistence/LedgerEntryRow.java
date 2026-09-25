package com.slice.wallet.persistence;

import com.slice.wallet.domain.Currency;
import com.slice.wallet.domain.EntryType;
import com.slice.wallet.domain.InitiatorType;
import com.slice.wallet.domain.LedgerEntry;
import com.slice.wallet.domain.Money;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The {@code ledger_entries} row.
 *
 * <p>Every column is {@code updatable = false} and the class has no setters. That is not style:
 * an append-only ledger should be append-only <i>by construction</i>, so that "just fix the
 * amount on that row" is not something a future maintainer can express. Hibernate will never
 * issue an {@code UPDATE} against this table, because there is no updatable column to put in one.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntryRow {

    @Id
    @Column(name = "entry_id", length = 36, nullable = false, updatable = false)
    private String entryId;

    @Column(name = "wallet_id", length = 36, nullable = false, updatable = false)
    private String walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 8, nullable = false, updatable = false)
    private EntryType entryType;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 8, nullable = false, updatable = false)
    private Currency currency;

    @Column(name = "balance_after_minor", nullable = false, updatable = false)
    private long balanceAfterMinor;

    @Column(name = "request_id", length = 64, nullable = false, updatable = false)
    private String requestId;

    @Column(name = "transfer_id", length = 36, updatable = false)
    private String transferId;

    @Column(name = "reversal_of", length = 36, updatable = false)
    private String reversalOf;

    @Column(name = "reason", length = 200, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiator_type", length = 16, nullable = false, updatable = false)
    private InitiatorType initiatorType;

    @Column(name = "initiated_by", length = 64, nullable = false, updatable = false)
    private String initiatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntryRow() {
    }

    public static LedgerEntryRow of(LedgerEntry entry) {
        LedgerEntryRow row = new LedgerEntryRow();
        row.entryId = entry.entryId();
        row.walletId = entry.walletId();
        row.entryType = entry.type();
        row.amountMinor = entry.amount().minor();
        row.currency = entry.amount().currency();
        row.balanceAfterMinor = entry.balanceAfterMinor();
        row.requestId = entry.requestId();
        row.transferId = entry.transferId();
        row.reversalOf = entry.reversalOf();
        row.reason = entry.reason();
        row.initiatorType = entry.initiatorType();
        row.initiatedBy = entry.initiatedBy();
        row.createdAt = entry.at();
        return row;
    }

    public LedgerEntry toDomain() {
        return new LedgerEntry(entryId, walletId, entryType,
                Money.ofMinor(amountMinor, currency), balanceAfterMinor,
                requestId, transferId, reversalOf, reason,
                initiatorType, initiatedBy, createdAt);
    }

    public String entryId() {
        return entryId;
    }

    public String walletId() {
        return walletId;
    }

    public String transferId() {
        return transferId;
    }

    public String reversalOf() {
        return reversalOf;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
