package com.slice.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for a reversal.
 *
 * <p>The reason is <b>mandatory</b>, and the database enforces it too
 * ({@code ck_ledger_reason_iff_reversal}). A refund with no recorded reason is unauditable: six
 * months later nobody can tell a goodwill credit from a fraud clawback from a mistake.
 */
public record ReversalRequest(
        @NotBlank(message = "reason is required for a reversal")
        @Size(max = 200, message = "reason must be at most 200 characters")
        String reason) {
}
