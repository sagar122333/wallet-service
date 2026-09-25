package com.slice.wallet.api;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tests that justify the locking, the lock ordering and the constraints.
 *
 * <p>Every one releases its threads from a single latch, so the calls genuinely overlap.
 * Starting threads in a loop and hoping is not a concurrency test; it passes on a busy machine
 * for the wrong reason. There is no {@code Thread.sleep} anywhere in this file.
 *
 * <p>These run against real MySQL, so they exercise the real {@code SELECT ... FOR UPDATE} and
 * the real unique constraints - which is the entire point. On H2 they would prove nothing.
 */
class ConcurrencyIT extends ApiTestBase {

    private String rw(String userId) {
        return token(userId, "wallet:read", "wallet:write");
    }

    @Test
    @DisplayName("32 concurrent debits against 10 rupees: exactly 10 succeed, balance lands on zero")
    void concurrentDebitsNeverOverdraw() throws Exception {
        String user = newUserId();
        String token = rw(user);
        String wallet = createWallet(token, "INR");
        credit(wallet, token, 1_000);   // 10.00

        List<ResponseEntity<JsonNode>> responses = runTogether(32, index ->
                post("/v1/wallets/" + wallet + "/debits", token, "spend-" + index,
                        Map.of("amountMinor", 100, "currency", "INR")));

        long succeeded = responses.stream().filter(r -> r.getStatusCode().value() == 201).count();
        long refused = responses.stream()
                .filter(r -> r.getStatusCode().value() == 422)
                .filter(r -> "INSUFFICIENT_FUNDS".equals(errorCode(r)))
                .count();

        assertEquals(10, succeeded, "exactly ten one-rupee spends fit in ten rupees");
        assertEquals(22, refused, "and the other 22 are refused for the right reason");
        assertEquals(0L, balanceMinor(wallet, token), "the balance lands exactly on zero");
        assertTrue(reconciled(wallet, user), "no entry was lost under contention");
    }

    @Test
    @DisplayName("16 concurrent retries of one key credit the wallet exactly once")
    void concurrentRetriesOfOneKeyPostOnce() throws Exception {
        String user = newUserId();
        String token = rw(user);
        String wallet = createWallet(token, "INR");
        String sharedKey = newKey();

        List<ResponseEntity<JsonNode>> responses = runTogether(16, index ->
                post("/v1/wallets/" + wallet + "/credits", token, sharedKey,
                        Map.of("amountMinor", 25_000, "currency", "INR")));

        Set<String> transactionIds = new HashSet<>();
        int created = 0;
        int inFlight = 0;
        for (ResponseEntity<JsonNode> response : responses) {
            if (response.getStatusCode().value() == 201) {
                created++;
                transactionIds.add(response.getBody().get("transactionId").asText());
            } else {
                // A duplicate arriving while the first attempt is still running has nothing to
                // replay yet. 409 "retry shortly" is the correct answer, not a second execution.
                assertEquals(409, response.getStatusCode().value());
                assertEquals("REQUEST_IN_FLIGHT", errorCode(response));
                inFlight++;
            }
        }

        assertTrue(created >= 1, "at least one caller must get the real answer");
        assertEquals(1, transactionIds.size(), "every success is the SAME transaction");
        assertEquals(16, created + inFlight, "every caller got a definite answer");
        assertEquals(25_000L, balanceMinor(wallet, token), "the money moved exactly once");
        assertTrue(reconciled(wallet, user), "one entry, one balance");
    }

    @Test
    @DisplayName("transfers in opposite directions do not deadlock and conserve money")
    void oppositeTransfersDoNotDeadlock() throws Exception {
        String asha = newUserId();
        String bilal = newUserId();
        String ashaToken = rw(asha);
        String bilalToken = rw(bilal);
        String a = createWallet(ashaToken, "INR");
        String b = createWallet(bilalToken, "INR");
        credit(a, ashaToken, 100_000);
        credit(b, bilalToken, 100_000);

        // Half the threads push A->B while the other half push B->A. Without a total order over
        // the two row locks this is the textbook deadlock and the pool never terminates.
        List<ResponseEntity<JsonNode>> responses = runTogether(16, index -> {
            boolean forward = index % 2 == 0;
            return post("/v1/transfers", forward ? ashaToken : bilalToken, "xfer-" + index,
                    Map.of("fromWalletId", forward ? a : b,
                            "toWalletId", forward ? b : a,
                            "amountMinor", 1_000,
                            "currency", "INR"));
        });

        long failures = responses.stream()
                .filter(r -> r.getStatusCode().value() >= 500)
                .count();
        assertEquals(0, failures, "a deadlock that escapes the retry would surface as a 5xx");
        assertEquals(200_000L, balanceMinor(a, ashaToken) + balanceMinor(b, bilalToken),
                "money is conserved: nothing was created or destroyed in transit");
        assertTrue(reconciled(a, asha), "A reconciles");
        assertTrue(reconciled(b, bilal), "B reconciles");
    }

    @Test
    @DisplayName("two concurrent reversals of one transaction refund exactly once")
    void concurrentReversalsPostOnce() throws Exception {
        String user = newUserId();
        String token = rw(user);
        String ops = token(newUserId(), "wallet:read", "wallet:reverse", "wallet:admin");
        String wallet = createWallet(token, "INR");
        credit(wallet, token, 10_000);
        String txn = post("/v1/wallets/" + wallet + "/debits", token, newKey(),
                Map.of("amountMinor", 4_000, "currency", "INR"))
                .getBody().get("transactionId").asText();

        // DIFFERENT idempotency keys, so the idempotency table cannot help here. The only thing
        // standing between this and a double refund is uq_ledger_reversal_of.
        List<ResponseEntity<JsonNode>> responses = runTogether(4, index ->
                post("/v1/transactions/" + txn + "/reversals", ops, "reversal-" + index,
                        Map.of("reason", "duplicate charge")));

        long succeeded = responses.stream().filter(r -> r.getStatusCode().value() == 201).count();
        assertEquals(1, succeeded, "exactly one reversal may win");
        responses.stream().filter(r -> r.getStatusCode().value() != 201).forEach(r ->
                assertEquals(409, r.getStatusCode().value(), "the losers see a conflict, not a 500"));
        assertEquals(10_000L, balanceMinor(wallet, token), "refunded once, not four times");
        assertTrue(reconciled(wallet, user), "the ledger still matches the balance");
    }

    // ------------------------------------------------------------------ harness

    private boolean reconciled(String walletId, String ownerUserId) {
        String admin = token(ownerUserId, "wallet:read", "wallet:admin");
        return get("/v1/wallets/" + walletId + "/reconciliation", admin)
                .getBody().get("inSync").asBoolean();
    }

    /** Releases every thread from one latch, so the requests genuinely overlap. */
    private <T> List<T> runTogether(int threads, ThrowingFunction<T> body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                Callable<T> task = () -> {
                    ready.countDown();
                    go.await();
                    return body.apply(index);
                };
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "all threads reached the start line");
            go.countDown();

            List<T> results = new ArrayList<>(threads);
            for (Future<T> future : futures) {
                // A bounded get, so a deadlock fails the test in 60 seconds instead of hanging
                // the build forever.
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T> {
        T apply(int index) throws Exception;
    }
}
