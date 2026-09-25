package com.slice.wallet.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.WalletException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Transport-level idempotency: the same {@code Idempotency-Key} from the same caller returns the
 * same response and performs the work at most once.
 *
 * <p><b>Why this is a table and not just the ledger's unique constraint.</b> The ledger's
 * {@code UNIQUE (wallet_id, request_id)} guarantees the money moves once, which is the thing that
 * actually matters - but it cannot answer a retry with the <i>original response</i>, and it does
 * not exist at all for a request that wrote no ledger row. This table does both, and the
 * constraint stays underneath it as the sink-level backstop. Two layers, and only the lower one
 * is a guarantee.
 *
 * <p><b>Why every step runs in its own transaction.</b> The claim must be <i>committed</i> before
 * the work starts, or a concurrent duplicate cannot see it. And a failed flush poisons its
 * persistence context - Hibernate keeps the failed statement queued and re-triggers it on the
 * next operation, so the recovery read has to happen on a fresh one. {@code TransactionTemplate}
 * with {@code PROPAGATION_REQUIRES_NEW} is used rather than {@code @Transactional} because
 * annotation-driven propagation only applies to calls that pass through the Spring proxy, and
 * every call here is a plain internal call.
 *
 * <p><b>What is cached, and what is not.</b> Only successful terminal responses. Any failure
 * releases the key, so the client's retry genuinely re-runs. That is the right behaviour for a
 * 5xx, and it is a deliberate simplification for a 4xx: caching a business rejection would need
 * the stored body to be an error envelope rather than the endpoint's own response type, which in
 * turn means replaying raw bytes instead of a typed object. Production systems (Stripe among
 * them) do exactly that; here the typed version is clearer and the cost is that a retried
 * rejection is re-evaluated and fails the same way.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** What a handler produces: the status to return, the body, and the id it created. */
    public record Attempt<T>(int status, T body, String resourceId) {
    }

    /** What the caller gets back. {@code replayed} drives the Idempotent-Replay header. */
    public record Outcome<T>(int status, T body, boolean replayed) {
    }

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyRepository repository,
                              ObjectMapper objectMapper,
                              Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs {@code work} at most once for this (caller, key) pair.
     *
     * @param userId        the authenticated caller - the key's namespace
     * @param key           the caller's Idempotency-Key header
     * @param fingerprint   hash of method + path + body, from {@link IdempotencyKeys}
     * @param responseType  the type to deserialise a replayed body into
     * @param work          the handler. Must be safe to not run at all (on a replay)
     */
    public <T> Outcome<T> execute(String userId, String key, String fingerprint,
                                  Class<T> responseType, Supplier<Attempt<T>> work) {
        requireUsableKey(key);
        String scopeKey = IdempotencyKeys.scopeKey(userId, key);

        Optional<IdempotencyRow> existing = claim(scopeKey, fingerprint);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, responseType);
        }

        try {
            Attempt<T> attempt = work.get();
            complete(scopeKey, attempt);
            return new Outcome<>(attempt.status(), attempt.body(), false);
        } catch (RuntimeException e) {
            // Release the key so the client's retry actually runs. For a 5xx this is essential -
            // the whole reason they are retrying is that we failed - and for a 4xx it means the
            // rejection is simply re-evaluated.
            release(scopeKey);
            throw e;
        }
    }

    private void requireUsableKey(String key) {
        if (key == null || key.isBlank()) {
            throw new WalletException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "the Idempotency-Key header is required on every mutating request");
        }
        if (key.length() > IdempotencyKeys.MAX_KEY_LENGTH) {
            throw new WalletException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be at most " + IdempotencyKeys.MAX_KEY_LENGTH + " characters");
        }
    }

    /**
     * Claims the key. Returns empty when this call won the claim, or the existing record when
     * somebody else got there first.
     *
     * <p>The read first is only a fast path for the common case (a retry of something already
     * finished). The <b>insert</b> is the claim: it is a single atomic operation against a unique
     * primary key, where a read-then-insert would be a check-then-act race in which two
     * simultaneous retries both see "absent" and both execute.
     */
    private Optional<IdempotencyRow> claim(String scopeKey, String fingerprint) {
        Optional<IdempotencyRow> alreadyThere = requiresNew.execute(s -> repository.findById(scopeKey));
        if (alreadyThere != null && alreadyThere.isPresent()) {
            return alreadyThere;
        }
        try {
            requiresNew.executeWithoutResult(s ->
                    repository.saveAndFlush(IdempotencyRow.inFlight(scopeKey, fingerprint, clock.instant())));
            return Optional.empty();
        } catch (DataIntegrityViolationException lostTheRace) {
            // Somebody inserted between our read and our insert. Their row is the truth; read it
            // on a fresh transaction, because this one's persistence context is now unusable.
            Optional<IdempotencyRow> winner = requiresNew.execute(s -> repository.findById(scopeKey));
            if (winner == null || winner.isEmpty()) {
                // Vanishingly rare: the winner failed and released the key between our insert
                // failing and this read. Tell the client to retry rather than guess.
                throw new WalletException(ErrorCode.REQUEST_IN_FLIGHT,
                        "a request with this Idempotency-Key is being processed; retry shortly");
            }
            return winner;
        }
    }

    private <T> Outcome<T> replay(IdempotencyRow row, String fingerprint, Class<T> responseType) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new WalletException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "this Idempotency-Key was already used with a different request");
        }
        if (row.state() == IdempotencyState.IN_FLIGHT) {
            throw new WalletException(ErrorCode.REQUEST_IN_FLIGHT,
                    "a request with this Idempotency-Key is being processed; retry shortly");
        }
        try {
            T body = objectMapper.readValue(row.responseBody(), responseType);
            return new Outcome<>(row.responseStatus(), body, true);
        } catch (JsonProcessingException e) {
            // Our own stored payload no longer deserialises - a response DTO changed shape under
            // a live key. Fail loudly; silently re-running the work would move money twice.
            throw new WalletException(ErrorCode.INTERNAL_ERROR,
                    "stored idempotent response could not be read", e);
        }
    }

    private <T> void complete(String scopeKey, Attempt<T> attempt) {
        String body;
        try {
            body = objectMapper.writeValueAsString(attempt.body());
        } catch (JsonProcessingException e) {
            throw new WalletException(ErrorCode.INTERNAL_ERROR, "response could not be serialised", e);
        }
        requiresNew.executeWithoutResult(s -> repository.findById(scopeKey).ifPresent(row -> {
            row.complete(attempt.status(), body, attempt.resourceId(), clock.instant());
            repository.save(row);
        }));
    }

    private void release(String scopeKey) {
        try {
            requiresNew.executeWithoutResult(s -> repository.deleteById(scopeKey));
        } catch (RuntimeException e) {
            // Never let cleanup mask the original failure - that is the exception the caller
            // needs to see. A stranded IN_FLIGHT row is swept by IdempotencySweeper.
            log.warn("could not release idempotency key {}", scopeKey, e);
        }
    }
}
