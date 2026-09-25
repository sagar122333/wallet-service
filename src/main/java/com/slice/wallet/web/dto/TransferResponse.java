package com.slice.wallet.web.dto;

import com.slice.wallet.service.TransferService;

/** Both legs of a transfer, so the caller can see the effect on each side in one response. */
public record TransferResponse(String transferId,
                               TransactionResponse debit,
                               TransactionResponse credit) {

    public static TransferResponse from(TransferService.TransferResult result) {
        return new TransferResponse(
                result.transferId(),
                TransactionResponse.from(result.debitLeg()),
                TransactionResponse.from(result.creditLeg()));
    }
}
