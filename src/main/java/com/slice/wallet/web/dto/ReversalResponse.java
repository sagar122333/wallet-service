package com.slice.wallet.web.dto;

import com.slice.wallet.service.ReversalService;

import java.util.List;

/**
 * The compensating entries a reversal posted - one for a standalone movement, two for a transfer.
 *
 * <p>A list rather than a single "balanceAfter", because a transfer reversal touches two wallets
 * and there is no single balance that honestly describes the outcome.
 */
public record ReversalResponse(String reversedId, List<TransactionResponse> compensatingEntries) {

    public static ReversalResponse from(ReversalService.ReversalResult result) {
        return new ReversalResponse(
                result.reversedId(),
                result.compensatingEntries().stream().map(TransactionResponse::from).toList());
    }
}
