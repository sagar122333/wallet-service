package com.slice.wallet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.slice.wallet.domain.EntryType;
import com.slice.wallet.domain.InitiatorType;
import com.slice.wallet.domain.LedgerEntry;
import com.slice.wallet.idempotency.IdempotencyRepository;
import com.slice.wallet.idempotency.IdempotencyRow;
import com.slice.wallet.idempotency.IdempotencyState;
import com.slice.wallet.persistence.LedgerEntryRepository;
import com.slice.wallet.persistence.LedgerEntryRow;
import com.slice.wallet.persistence.WalletRepository;
import com.slice.wallet.persistence.WalletRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Call the API, then go and look at the rows.
 *
 * <p>These are the tests that catch what a response body cannot tell you: that the wallet row
 * really was updated, that exactly one ledger row was written and no more, that the columns the
 * API never returns - {@code request_id}, {@code initiated_by}, {@code version} - hold what they
 * should, and that a failed request left nothing behind.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. A test transaction would roll back at the
 * end and, worse, would be a different transaction from the one the server used - the HTTP call
 * runs on a container thread and commits independently, so a test-side transaction could not see
 * its writes anyway. Reading committed data is the whole point here.
 */
class WalletDbIT extends ApiTestBase {

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private LedgerEntryRepository ledger;

    @Autowired
    private IdempotencyRepository idempotencyRecords;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ helpers

    private WalletRow walletRow(String walletId) {
        return wallets.findById(walletId).orElseThrow(() ->
                new AssertionError("no wallets row for " + walletId));
    }

    private List<LedgerEntry> entriesOf(String walletId) {
        return ledger.findPage(walletId, null, null, PageRequest.of(0, 100)).stream()
                .map(LedgerEntryRow::toDomain)
                .toList();
    }

    private int rawCount(String walletId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE wallet_id = ?", Integer.class, walletId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a credit updates the wallet row and writes exactly one ledger row")
    void creditWritesTheExpectedRows() {
        String user = newUserId();
        String token = token(user, "wallet:read", "wallet:write");
        String walletId = createWallet(token, "INR");
        String key = newKey();

        long versionBefore = walletRow(walletId).version();

        ResponseEntity<JsonNode> response = post("/v1/wallets/" + walletId + "/credits", token, key,
                Map.of("amountMinor", 50_000, "currency", "INR"));
        assertEquals(201, response.getStatusCode().value());

        // --- the wallets row
        WalletRow wallet = walletRow(walletId);
        assertEquals(50_000L, wallet.balanceMinor(), "the cached balance was updated");
        assertEquals(user, wallet.userId(), "the owner came from the token, not the body");
        assertTrue(wallet.version() > versionBefore,
                "the @Version column moved, which is how a lost update would be caught");

        // --- the ledger row
        assertEquals(1, rawCount(walletId), "exactly one row, counted in raw SQL");
        LedgerEntry entry = ledger.findByWalletIdAndRequestId(walletId, key).orElseThrow().toDomain();
        assertEquals(EntryType.CREDIT, entry.type());
        assertEquals(50_000L, entry.amount().minor());
        assertEquals(50_000L, entry.balanceAfterMinor(), "the audit anchor matches the new balance");
        assertEquals(key, entry.requestId(), "the Idempotency-Key IS the ledger's request_id");
        assertEquals(InitiatorType.USER, entry.initiatorType());
        assertEquals(user, entry.initiatedBy(), "who moved the money is recorded");
        assertNull(entry.transferId(), "a standalone credit belongs to no transfer");
        assertNull(entry.reversalOf());
        assertNull(entry.reason(), "and carries no reason - only a reversal does");

        // --- the idempotency record
        IdempotencyRow record = idempotencyRecords.findById(user + "|" + key).orElseThrow();
        assertEquals(IdempotencyState.COMPLETED, record.state());
        assertEquals(201, record.responseStatus().intValue(), "the exact status a retry will replay");
        assertNotNull(record.responseBody());
        assertEquals(entry.entryId(), record.resourceId());
    }

    @Test
    @DisplayName("a retried credit writes no second row")
    void retryWritesNothingNew() {
        String user = newUserId();
        String token = token(user, "wallet:read", "wallet:write");
        String walletId = createWallet(token, "INR");
        String key = newKey();
        Map<String, Object> body = Map.of("amountMinor", 25_000, "currency", "INR");

        post("/v1/wallets/" + walletId + "/credits", token, key, body);
        post("/v1/wallets/" + walletId + "/credits", token, key, body);
        post("/v1/wallets/" + walletId + "/credits", token, key, body);

        assertEquals(1, rawCount(walletId), "three requests, one ledger row");
        assertEquals(25_000L, walletRow(walletId).balanceMinor(), "the money moved once");
    }

    @Test
    @DisplayName("a refused debit leaves no trace at all")
    void failedDebitWritesNothing() {
        String user = newUserId();
        String token = token(user, "wallet:read", "wallet:write");
        String walletId = createWallet(token, "INR");
        credit(walletId, token, 10_000);
        long versionAfterCredit = walletRow(walletId).version();
        String key = newKey();

        ResponseEntity<JsonNode> response = post("/v1/wallets/" + walletId + "/debits", token, key,
                Map.of("amountMinor", 10_001, "currency", "INR"));
        assertEquals(422, response.getStatusCode().value());

        assertEquals(1, rawCount(walletId), "no ledger row was written for the refused debit");
        assertEquals(10_000L, walletRow(walletId).balanceMinor(), "the balance is untouched");
        assertEquals(versionAfterCredit, walletRow(walletId).version(),
                "and the row was not written at all - the transaction rolled back");
        assertTrue(idempotencyRecords.findById(user + "|" + key).isEmpty(),
                "the key was released, so the client's retry genuinely re-runs");
    }

    @Test
    @DisplayName("both legs of a transfer share a transfer_id AND the caller's request_id")
    void transferWritesTwoLinkedRows() {
        String asha = newUserId();
        String bilal = newUserId();
        String ashaToken = token(asha, "wallet:read", "wallet:write");
        String bilalToken = token(bilal, "wallet:read", "wallet:write");
        String from = createWallet(ashaToken, "INR");
        String to = createWallet(bilalToken, "INR");
        credit(from, ashaToken, 30_000);
        String key = newKey();

        ResponseEntity<JsonNode> response = post("/v1/transfers", ashaToken, key,
                Map.of("fromWalletId", from, "toWalletId", to,
                        "amountMinor", 12_000, "currency", "INR"));
        assertEquals(201, response.getStatusCode().value());
        String transferId = response.getBody().get("transferId").asText();

        List<LedgerEntryRow> legs = ledger.findByTransferIdOrderByCreatedAtAsc(transferId);
        assertEquals(2, legs.size());

        LedgerEntry debit = legs.stream().map(LedgerEntryRow::toDomain)
                .filter(e -> e.type() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry creditLeg = legs.stream().map(LedgerEntryRow::toDomain)
                .filter(e -> e.type() == EntryType.CREDIT).findFirst().orElseThrow();

        assertEquals(from, debit.walletId());
        assertEquals(to, creditLeg.walletId());
        // THE POINT OF THE COMPOSITE CONSTRAINT: both legs carry the caller's key unmodified,
        // which is only legal because uniqueness is (wallet_id, request_id) and not request_id
        // alone. A global constraint would have forced a synthetic suffix onto one of them.
        assertEquals(key, debit.requestId());
        assertEquals(key, creditLeg.requestId());
        assertEquals(0L, debit.signedMinor() + creditLeg.signedMinor(),
                "the two legs sum to zero - money was moved, not created");

        assertEquals(18_000L, walletRow(from).balanceMinor());
        assertEquals(12_000L, walletRow(to).balanceMinor());
    }

    @Test
    @DisplayName("a reversal appends a compensating row and never edits the original")
    void reversalAppendsRatherThanEdits() {
        String user = newUserId();
        String token = token(user, "wallet:read", "wallet:write");
        String ops = token(newUserId(), "wallet:read", "wallet:reverse", "wallet:admin");
        String walletId = createWallet(token, "INR");
        credit(walletId, token, 10_000);

        String spendKey = newKey();
        String spendId = post("/v1/wallets/" + walletId + "/debits", token, spendKey,
                Map.of("amountMinor", 4_000, "currency", "INR"))
                .getBody().get("transactionId").asText();

        LedgerEntry originalBefore = ledger.findById(spendId).orElseThrow().toDomain();

        ResponseEntity<JsonNode> reversal = post("/v1/transactions/" + spendId + "/reversals", ops,
                newKey(), Map.of("reason", "duplicate charge"));
        assertEquals(201, reversal.getStatusCode().value());

        // the original is byte-for-byte unchanged
        assertEquals(originalBefore, ledger.findById(spendId).orElseThrow().toDomain(),
                "an append-only ledger corrects by compensation, never by update");

        LedgerEntry compensating = ledger.findByReversalOf(spendId).orElseThrow().toDomain();
        assertEquals(EntryType.CREDIT, compensating.type(), "the opposite of the original DEBIT");
        assertEquals(4_000L, compensating.amount().minor());
        assertEquals("duplicate charge", compensating.reason(), "a reversal must say why");
        assertEquals(InitiatorType.OPS, compensating.initiatorType(),
                "an operator acted on a wallet they do not own");

        assertEquals(3, rawCount(walletId), "credit, debit, compensating credit");
        assertEquals(10_000L, walletRow(walletId).balanceMinor(), "the customer is made whole");
    }

    @Test
    @DisplayName("the cached balance always equals the fold over the ledger")
    void balanceMatchesTheLedger() {
        String user = newUserId();
        String token = token(user, "wallet:read", "wallet:write");
        String walletId = createWallet(token, "INR");
        credit(walletId, token, 50_000);
        post("/v1/wallets/" + walletId + "/debits", token, newKey(),
                Map.of("amountMinor", 12_345, "currency", "INR"));

        // Straight from the SQL view the migrations create - the same check an operator would
        // run at 3am, not a reimplementation of it in Java.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT cached_balance_minor, ledger_balance_minor, in_sync "
                        + "FROM wallet_reconciliation WHERE wallet_id = ?", walletId);

        assertEquals(37_655L, ((Number) row.get("cached_balance_minor")).longValue());
        assertEquals(37_655L, ((Number) row.get("ledger_balance_minor")).longValue());
        assertEquals(1, ((Number) row.get("in_sync")).intValue(),
                "if this is ever 0, the service has a bug");

        assertEquals(2, entriesOf(walletId).size());
    }
}
