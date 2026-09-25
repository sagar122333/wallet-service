package com.slice.wallet.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slice.wallet.domain.Currency;
import com.slice.wallet.domain.EntryType;
import com.slice.wallet.domain.InitiatorType;
import com.slice.wallet.domain.LedgerEntry;

import java.time.Instant;

/**
 * One ledger entry, as the API presents it.
 *
 * <p>A mapping layer rather than annotations on the entity, so the wire contract can be frozen
 * while the persistence model keeps changing - renaming a column then breaks a test here instead
 * of breaking every client silently.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(String transactionId,
                                  String walletId,
                                  EntryType type,
                                  long amountMinor,
                                  Currency currency,
                                  String amount,
                                  long balanceAfterMinor,
                                  String requestId,
                                  String transferId,
                                  String reversalOf,
                                  String reason,
                                  InitiatorType initiatorType,
                                  String initiatedBy,
                                  Instant at) {

    public static TransactionResponse from(LedgerEntry entry) {
        return new TransactionResponse(
                entry.entryId(),
                entry.walletId(),
                entry.type(),
                entry.amount().minor(),
                entry.amount().currency(),
                entry.amount().toDecimal().toPlainString(),
                entry.balanceAfterMinor(),
                entry.requestId(),
                entry.transferId(),
                entry.reversalOf(),
                entry.reason(),
                entry.initiatorType(),
                entry.initiatedBy(),
                entry.at());
    }
}
