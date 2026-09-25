package com.slice.wallet.api;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The API contract: status codes, error codes, headers, authorisation and idempotency.
 *
 * <p>Every test builds its own fixtures, so they can run in any order, individually, and in
 * parallel. No shared mutable fields and no {@code @Order}.
 */
class WalletApiIT extends ApiTestBase {

    private String readWriteToken(String userId) {
        return token(userId, "wallet:read", "wallet:write");
    }

    // ------------------------------------------------------------------ authentication

    @Test
    @DisplayName("the health probe needs no token")
    void healthIsOpen() {
        assertEquals(200, rest.getForEntity("/actuator/health", String.class).getStatusCode().value(),
                "a probe has no credentials, and one that can fail for an auth reason lies");
    }

    @Test
    @DisplayName("a request with no Authorization header never reaches a controller")
    void missingTokenIsUnauthenticated() {
        ResponseEntity<JsonNode> noToken = get("/v1/wallets", null);

        assertEquals(401, noToken.getStatusCode().value());
        assertEquals("UNAUTHENTICATED", errorCode(noToken),
                "and it comes back in the same envelope as every other error");
    }

    @Test
    @DisplayName("a non-Bearer Authorization header is also 401")
    void wrongAuthSchemeIsUnauthenticated() {
        var wrongScheme = new org.springframework.http.HttpHeaders();
        wrongScheme.set("Authorization", "Basic dXNlcjpwYXNz");

        ResponseEntity<JsonNode> response = rest.exchange("/v1/wallets", HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(wrongScheme), JsonNode.class);

        assertEquals(401, response.getStatusCode().value());
        // A token that user-management rejects is covered by BearerAuthenticationFilterTest -
        // under wallet.auth.mode=stub every non-blank token is deliberately accepted, so that
        // case cannot be provoked from here.
    }

    @Test
    @DisplayName("a read-only token cannot move money")
    void readOnlyTokenCannotWrite() {
        String user = newUserId();
        String wallet = createWallet(readWriteToken(user), "INR");

        ResponseEntity<JsonNode> response = post("/v1/wallets/" + wallet + "/credits",
                token(user, "wallet:read"), newKey(), Map.of("amountMinor", 100, "currency", "INR"));

        assertEquals(403, response.getStatusCode().value(),
                "authenticated but not authorised - a retry with the same token never helps");
        assertEquals("FORBIDDEN", errorCode(response));
    }

    @Test
    @DisplayName("another user's wallet is 404, not 403")
    void anotherUsersWalletIsNotFound() {
        String wallet = createWallet(readWriteToken(newUserId()), "INR");

        ResponseEntity<JsonNode> response = get("/v1/wallets/" + wallet, readWriteToken(newUserId()));

        assertEquals(404, response.getStatusCode().value(),
                "a 403 would confirm the id is real and make this a wallet-id oracle");
        assertEquals("WALLET_NOT_FOUND", errorCode(response));
    }

    @Test
    @DisplayName("the owner of a new wallet comes from the token, not the body")
    void ownerComesFromTheToken() {
        String caller = newUserId();
        ResponseEntity<JsonNode> response = post("/v1/wallets", readWriteToken(caller), newKey(),
                Map.of("currency", "INR", "userId", "somebody-else"));

        assertEquals(403, response.getStatusCode().value(),
                "naming another owner needs wallet:admin - otherwise this is impersonation");
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("a mutating request with no Idempotency-Key is refused")
    void idempotencyKeyIsRequired() {
        String user = newUserId();
        String wallet = createWallet(readWriteToken(user), "INR");

        ResponseEntity<JsonNode> response = post("/v1/wallets/" + wallet + "/credits",
                readWriteToken(user), null, Map.of("amountMinor", 100, "currency", "INR"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", errorCode(response),
                "optional idempotency means the one caller who forgets double-charges someone");
    }

    @Test
    @DisplayName("a decimal amount, a zero amount and a negative amount are all 400")
    void amountsAreValidated() {
        String user = newUserId();
        String token = readWriteToken(user);
        String wallet = createWallet(token, "INR");
        String path = "/v1/wallets/" + wallet + "/credits";

        assertEquals(400, post(path, token, newKey(),
                Map.of("amountMinor", 100.50, "currency", "INR")).getStatusCode().value(),
                "money on the wire is an integer count of paise");
        assertEquals(400, post(path, token, newKey(),
                Map.of("amountMinor", 0, "currency", "INR")).getStatusCode().value(),
                "a zero-amount movement is meaningless");
        assertEquals(400, post(path, token, newKey(),
                Map.of("amountMinor", -500, "currency", "INR")).getStatusCode().value(),
                "a negative credit is a debit in disguise");
    }

    @Test
    @DisplayName("an unknown field is rejected, not ignored")
    void unknownFieldIsRejected() {
        String user = newUserId();
        String token = readWriteToken(user);
        String wallet = createWallet(token, "INR");

        ResponseEntity<JsonNode> response = post("/v1/wallets/" + wallet + "/credits", token, newKey(),
                Map.of("amountMinor", 100, "currency", "INR", "amount", "1.00"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("UNKNOWN_FIELD", errorCode(response),
                "silently dropping a field is how a client ships a bug it cannot see");
    }

    @Test
    @DisplayName("validation errors name every offending field")
    void validationErrorsAreDetailed() {
        String token = readWriteToken(newUserId());

        ResponseEntity<JsonNode> response = post("/v1/transfers", token, newKey(),
                Map.of("fromWalletId", "", "toWalletId", "", "amountMinor", 0, "currency", "INR"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("VALIDATION_FAILED", errorCode(response));
        assertTrue(response.getBody().get("error").get("details").size() >= 3,
                "a red build should tell you which fields, not just that something was wrong");
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("the same key returns the same transaction and moves money once")
    void retryReplaysTheOriginalResponse() {
        String user = newUserId();
        String token = readWriteToken(user);
        String wallet = createWallet(token, "INR");
        String key = newKey();
        Map<String, Object> body = Map.of("amountMinor", 50_000, "currency", "INR");

        ResponseEntity<JsonNode> first = post("/v1/wallets/" + wallet + "/credits", token, key, body);
        ResponseEntity<JsonNode> retry = post("/v1/wallets/" + wallet + "/credits", token, key, body);

        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, retry.getStatusCode().value(), "the replay carries the original status too");
        assertEquals(first.getBody().get("transactionId"), retry.getBody().get("transactionId"));
        assertEquals("true", retry.getHeaders().getFirst("Idempotent-Replay"),
                "the client can tell that deduplication happened");
        assertNull(first.getHeaders().getFirst("Idempotent-Replay"));
        assertEquals(50_000L, balanceMinor(wallet, token), "credited once, not twice");
    }

    @Test
    @DisplayName("the same key with a different body is a conflict")
    void keyReuseWithADifferentBodyIsRejected() {
        String user = newUserId();
        String token = readWriteToken(user);
        String wallet = createWallet(token, "INR");
        String key = newKey();

        post("/v1/wallets/" + wallet + "/credits", token, key,
                Map.of("amountMinor", 50_000, "currency", "INR"));
        ResponseEntity<JsonNode> reused = post("/v1/wallets/" + wallet + "/credits", token, key,
                Map.of("amountMinor", 99_999, "currency", "INR"));

        assertEquals(409, reused.getStatusCode().value(),
                "replaying the first response here would swallow a real second payment");
        assertEquals("IDEMPOTENCY_KEY_REUSED", errorCode(reused));
    }

    @Test
    @DisplayName("one user's key cannot collide with another's")
    void keysAreScopedToTheCaller() {
        String asha = newUserId();
        String bilal = newUserId();
        String ashaToken = readWriteToken(asha);
        String bilalToken = readWriteToken(bilal);
        String ashaWallet = createWallet(ashaToken, "INR");
        String bilalWallet = createWallet(bilalToken, "INR");
        String sharedKey = "checkout-1";

        ResponseEntity<JsonNode> first = post("/v1/wallets/" + ashaWallet + "/credits",
                ashaToken, sharedKey, Map.of("amountMinor", 10_000, "currency", "INR"));
        ResponseEntity<JsonNode> second = post("/v1/wallets/" + bilalWallet + "/credits",
                bilalToken, sharedKey, Map.of("amountMinor", 20_000, "currency", "INR"));

        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, second.getStatusCode().value(),
                "the same key string from a different caller is a different key");
        assertNotEquals(first.getBody().get("transactionId"), second.getBody().get("transactionId"));
        assertEquals(10_000L, balanceMinor(ashaWallet, ashaToken));
        assertEquals(20_000L, balanceMinor(bilalWallet, bilalToken),
                "Bilal's money must not be swallowed by Asha's key");
    }

    // ------------------------------------------------------------------ business rules

    @Test
    @DisplayName("spending more than the balance is 422, and nothing changes")
    void insufficientFundsIsUnprocessable() {
        String user = newUserId();
        String token = readWriteToken(user);
        String wallet = createWallet(token, "INR");
        credit(wallet, token, 10_000);

        ResponseEntity<JsonNode> response = post("/v1/wallets/" + wallet + "/debits", token, newKey(),
                Map.of("amountMinor", 10_001, "currency", "INR"));

        assertEquals(422, response.getStatusCode().value(),
                "well-formed, authorised, and refused by a rule: not 400 and not 500");
        assertEquals("INSUFFICIENT_FUNDS", errorCode(response));
        assertEquals(10_000L, balanceMinor(wallet, token));
    }

    @Test
    @DisplayName("a transfer debits one wallet and credits the other by the same amount")
    void transferConservesMoney() {
        String asha = newUserId();
        String bilal = newUserId();
        String ashaToken = readWriteToken(asha);
        String bilalToken = readWriteToken(bilal);
        String from = createWallet(ashaToken, "INR");
        String to = createWallet(bilalToken, "INR");
        credit(from, ashaToken, 30_000);

        ResponseEntity<JsonNode> response = post("/v1/transfers", ashaToken, newKey(),
                Map.of("fromWalletId", from, "toWalletId", to, "amountMinor", 12_000, "currency", "INR"));

        assertEquals(201, response.getStatusCode().value());
        assertEquals(response.getBody().get("debit").get("transferId"),
                response.getBody().get("credit").get("transferId"),
                "both legs share one transferId, which is how the pair is found later");
        assertEquals(18_000L, balanceMinor(from, ashaToken));
        assertEquals(12_000L, balanceMinor(to, bilalToken));
    }

    @Test
    @DisplayName("a transfer to the same wallet, and across currencies, is refused")
    void invalidTransfersAreRefused() {
        String asha = newUserId();
        String ashaToken = readWriteToken(asha);
        String inr = createWallet(ashaToken, "INR");
        String usd = createWallet(ashaToken, "USD");
        credit(inr, ashaToken, 10_000);

        ResponseEntity<JsonNode> self = post("/v1/transfers", ashaToken, newKey(),
                Map.of("fromWalletId", inr, "toWalletId", inr, "amountMinor", 100, "currency", "INR"));
        assertEquals(422, self.getStatusCode().value());
        assertEquals("SAME_WALLET", errorCode(self));

        ResponseEntity<JsonNode> crossCurrency = post("/v1/transfers", ashaToken, newKey(),
                Map.of("fromWalletId", inr, "toWalletId", usd, "amountMinor", 100, "currency", "INR"));
        assertEquals(422, crossCurrency.getStatusCode().value());
        assertEquals("CURRENCY_MISMATCH", errorCode(crossCurrency),
                "conversion is a separate operation with a rate and an audit trail");
    }

    // ------------------------------------------------------------------ reversal

    @Test
    @DisplayName("a customer cannot reverse their own spend")
    void reversalRequiresTheOperatorScope() {
        String user = newUserId();
        String token = readWriteToken(user);
        String wallet = createWallet(token, "INR");
        credit(wallet, token, 10_000);
        String txn = post("/v1/wallets/" + wallet + "/debits", token, newKey(),
                Map.of("amountMinor", 4_000, "currency", "INR")).getBody().get("transactionId").asText();

        ResponseEntity<JsonNode> response = post("/v1/transactions/" + txn + "/reversals", token,
                newKey(), Map.of("reason", "changed my mind"));

        assertEquals(403, response.getStatusCode().value(),
                "a customer reversing their own spend is a refund they granted themselves");
    }

    @Test
    @DisplayName("an operator can reverse once, with a reason, and not twice")
    void operatorReversal() {
        String user = newUserId();
        String token = readWriteToken(user);
        String ops = token(newUserId(), "wallet:read", "wallet:reverse", "wallet:admin");
        String wallet = createWallet(token, "INR");
        credit(wallet, token, 10_000);
        String txn = post("/v1/wallets/" + wallet + "/debits", token, newKey(),
                Map.of("amountMinor", 4_000, "currency", "INR")).getBody().get("transactionId").asText();

        ResponseEntity<JsonNode> noReason =
                post("/v1/transactions/" + txn + "/reversals", ops, newKey(), Map.of("reason", ""));
        assertEquals(400, noReason.getStatusCode().value(),
                "a refund with no recorded reason is unauditable");

        ResponseEntity<JsonNode> reversal = post("/v1/transactions/" + txn + "/reversals", ops,
                newKey(), Map.of("reason", "duplicate charge"));
        assertEquals(201, reversal.getStatusCode().value());
        assertEquals(txn, reversal.getBody().get("compensatingEntries").get(0).get("reversalOf").asText());
        assertEquals("OPS", reversal.getBody().get("compensatingEntries").get(0).get("initiatorType").asText(),
                "the operator, not the wallet's owner, is recorded as the initiator");
        assertEquals(10_000L, balanceMinor(wallet, token), "the customer is made whole");

        ResponseEntity<JsonNode> again = post("/v1/transactions/" + txn + "/reversals", ops,
                newKey(), Map.of("reason", "again"));
        assertEquals(409, again.getStatusCode().value(), "a second reversal would refund twice");
        assertEquals("ALREADY_REVERSED", errorCode(again));
    }

    @Test
    @DisplayName("one leg of a transfer cannot be reversed on its own")
    void aTransferLegCannotBeReversedAlone() {
        String asha = newUserId();
        String bilal = newUserId();
        String ashaToken = readWriteToken(asha);
        String bilalToken = readWriteToken(bilal);
        String ops = token(newUserId(), "wallet:read", "wallet:reverse", "wallet:admin");
        String from = createWallet(ashaToken, "INR");
        String to = createWallet(bilalToken, "INR");
        credit(from, ashaToken, 10_000);

        JsonNode transfer = post("/v1/transfers", ashaToken, newKey(),
                Map.of("fromWalletId", from, "toWalletId", to, "amountMinor", 4_000, "currency", "INR"))
                .getBody();
        String debitLegId = transfer.get("debit").get("transactionId").asText();

        ResponseEntity<JsonNode> byLeg = post("/v1/transactions/" + debitLegId + "/reversals", ops,
                newKey(), Map.of("reason", "wrong recipient"));
        assertEquals(422, byLeg.getStatusCode().value(),
                "compensating one side would leave each wallet consistent while creating money");
        assertEquals("REVERSE_TRANSFER_AS_TRANSFER", errorCode(byLeg));

        ResponseEntity<JsonNode> byTransfer = post(
                "/v1/transfers/" + transfer.get("transferId").asText() + "/reversals", ops,
                newKey(), Map.of("reason", "wrong recipient"));
        assertEquals(201, byTransfer.getStatusCode().value());
        assertEquals(2, byTransfer.getBody().get("compensatingEntries").size(), "both legs compensated");
        assertEquals(10_000L, balanceMinor(from, ashaToken));
        assertEquals(0L, balanceMinor(to, bilalToken));
    }

    // ------------------------------------------------------------------ pagination and routing

    @Test
    @DisplayName("paging walks every transaction exactly once")
    void pagingIsComplete() {
        String user = newUserId();
        String token = readWriteToken(user);
        String wallet = createWallet(token, "INR");
        for (int i = 0; i < 12; i++) {
            credit(wallet, token, 100);
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            String path = "/v1/wallets/" + wallet + "/transactions?limit=5"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = get(path, token).getBody();
            pages++;
            page.get("items").forEach(item ->
                    assertTrue(seen.add(item.get("transactionId").asText()),
                            "no transaction may appear on two pages"));
            JsonNode next = page.get("nextCursor");
            if (next == null || next.isNull()) {
                break;
            }
            cursor = next.asText();
            assertTrue(pages < 10, "the paging loop must terminate");
        }

        assertEquals(12, seen.size());
        assertEquals(3, pages, "12 at 5 per page: 5, 5, 2");
    }

    @Test
    @DisplayName("a forged cursor and an oversized limit are both 400")
    void pagingInputIsValidated() {
        String user = newUserId();
        String token = readWriteToken(user);
        String wallet = createWallet(token, "INR");

        ResponseEntity<JsonNode> badCursor =
                get("/v1/wallets/" + wallet + "/transactions?cursor=zzzz", token);
        assertEquals(400, badCursor.getStatusCode().value());
        assertEquals("INVALID_CURSOR", errorCode(badCursor), "never a silently empty page");

        ResponseEntity<JsonNode> hugeLimit =
                get("/v1/wallets/" + wallet + "/transactions?limit=5000", token);
        assertEquals(400, hugeLimit.getStatusCode().value(),
                "a cap, not a clamp: silently returning 100 hides a broken client loop");
    }

    @Test
    @DisplayName("the wrong verb is 405 and an unknown path is 404")
    void routingErrors() {
        String token = readWriteToken(newUserId());

        assertEquals(405, exchange(HttpMethod.PUT, "/v1/wallets", token,
                Map.of("currency", "INR")).getStatusCode().value(),
                "405 not 404: the path is right and the client's bug is the verb");

        ResponseEntity<JsonNode> unknown = get("/v1/nope", token);
        assertEquals(404, unknown.getStatusCode().value());
        assertEquals("NO_SUCH_ROUTE", errorCode(unknown), "distinct from a missing resource");
    }

    @Test
    @DisplayName("an inbound request id is echoed, not replaced")
    void requestIdIsEchoed() {
        String token = readWriteToken(newUserId());
        var headers = headers(token, null);
        headers.set("X-Request-Id", "trace-me-42");

        ResponseEntity<JsonNode> response = rest.exchange("/v1/wallets", HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), JsonNode.class);

        assertEquals("trace-me-42", response.getHeaders().getFirst("X-Request-Id"),
                "it is how a trace spanning two services joins up");
    }

    @Test
    @DisplayName("the ledger always sums to the cached balance")
    void reconciliationHolds() {
        String user = newUserId();
        String token = readWriteToken(user);
        String ops = token(user, "wallet:read", "wallet:admin");
        String wallet = createWallet(token, "INR");
        credit(wallet, token, 50_000);
        post("/v1/wallets/" + wallet + "/debits", token, newKey(),
                Map.of("amountMinor", 12_345, "currency", "INR"));

        JsonNode reconciliation = get("/v1/wallets/" + wallet + "/reconciliation", ops).getBody();

        assertTrue(reconciliation.get("inSync").asBoolean(),
                "the cached balance must equal the fold over the ledger, always");
        assertEquals(37_655L, reconciliation.get("ledgerBalanceMinor").asLong());
        assertEquals(2L, reconciliation.get("entryCount").asLong());
    }
}
