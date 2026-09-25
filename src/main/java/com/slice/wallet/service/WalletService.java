package com.slice.wallet.service;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.IdGenerator;
import com.slice.wallet.common.WalletException;
import com.slice.wallet.domain.Currency;
import com.slice.wallet.domain.InitiatorType;
import com.slice.wallet.domain.LedgerEntry;
import com.slice.wallet.domain.Money;
import com.slice.wallet.domain.Posting;
import com.slice.wallet.domain.PostingContext;
import com.slice.wallet.domain.Wallet;
import com.slice.wallet.persistence.LedgerEntryRepository;
import com.slice.wallet.persistence.LedgerEntryRow;
import com.slice.wallet.persistence.WalletRepository;
import com.slice.wallet.persistence.WalletRow;
import com.slice.wallet.security.Caller;
import com.slice.wallet.security.Scopes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Wallet lifecycle and single-wallet money movement.
 *
 * <p><b>Where each guarantee lives.</b>
 * <ul>
 *   <li><i>No overdraft.</i> The guard is one line in {@link Wallet#debit}, and it only means
 *       anything because this class holds the wallet's row lock around it. Reading the balance
 *       before the lock would make it a check-then-act race.</li>
 *   <li><i>Idempotency.</i> Checked here under the lock by {@code (walletId, requestId)}, and
 *       guaranteed by the unique constraint of the same name. The HTTP layer's
 *       {@code Idempotency-Key} is a third layer that saves the work; this one saves the
 *       money.</li>
 *   <li><i>Authorisation.</i> Enforced here, not only in the filter chain, so a future Kafka
 *       consumer or scheduled job cannot route around it.</li>
 * </ul>
 *
 * <p><b>On the retry.</b> InnoDB resolves a deadlock by killing one transaction and expects the
 * application to retry; {@code innodb_lock_wait_timeout} fires on contention. Without the
 * annotation below both surface as a 500 and the caller's money movement is simply lost. The
 * retry is only safe <i>because</i> the operation is idempotent - the two features belong to the
 * same design. Spring applies the retry advisor outside the transaction advisor, so each attempt
 * gets a fresh transaction.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;
    private final LedgerEntryRepository ledger;
    private final Clock clock;
    private final IdGenerator ids;

    public WalletService(WalletRepository wallets, LedgerEntryRepository ledger,
                         Clock clock, IdGenerator ids) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.clock = clock;
        this.ids = ids;
    }

    // ---------------------------------------------------------------- lifecycle and reads

    /**
     * Creates a wallet.
     *
     * <p>The owner is the caller, not a field in the request body. A {@code userId} the client
     * can set would let any authenticated caller create a wallet in someone else's name, which
     * is an impersonation hole rather than a missing validation. An admin may name a different
     * owner - that is what onboarding flows need - and it is logged.
     */
    @Transactional
    public Wallet create(Caller caller, String requestedOwner, Currency currency) {
        caller.requireScope(Scopes.WRITE);

        String owner = caller.userId();
        if (requestedOwner != null && !requestedOwner.equals(caller.userId())) {
            caller.requireScope(Scopes.ADMIN);
            log.warn("admin {} is creating a wallet owned by {}", caller.userId(), requestedOwner);
            owner = requestedOwner;
        }

        // Pre-check for a clean 409; uq_wallets_user_currency is what actually guarantees it.
        if (wallets.existsByUserIdAndCurrency(owner, currency)) {
            throw new WalletException(ErrorCode.WALLET_ALREADY_EXISTS,
                    "this user already has a " + currency + " wallet");
        }
        return wallets.save(WalletRow.create(owner, currency, clock.instant())).toDomain();
    }

    @Transactional(readOnly = true)
    public Wallet get(String walletId, Caller caller) {
        caller.requireScope(Scopes.READ);
        return requireVisible(walletId, caller).toDomain();
    }

    @Transactional(readOnly = true)
    public List<Wallet> listOwn(Caller caller) {
        caller.requireScope(Scopes.READ);
        return wallets.findByUserIdOrderByCreatedAtDesc(caller.userId())
                .stream().map(WalletRow::toDomain).toList();
    }

    // ---------------------------------------------------------------- money movement

    @Retryable(retryFor = {PessimisticLockingFailureException.class, CannotAcquireLockException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 25, multiplier = 2.0, random = true))
    @Transactional
    public LedgerEntry credit(String walletId, Caller caller, Money amount, String requestId) {
        caller.requireScope(Scopes.WRITE);
        WalletRow row = lockVisible(walletId, caller);

        Optional<LedgerEntryRow> replay = ledger.findByWalletIdAndRequestId(walletId, requestId);
        if (replay.isPresent()) {
            return replay.get().toDomain();
        }

        Wallet wallet = row.toDomain();
        Instant now = clock.instant();
        Posting posting = wallet.credit(amount, context(row, caller, requestId, now));
        return persist(row, posting, now);
    }

    @Retryable(retryFor = {PessimisticLockingFailureException.class, CannotAcquireLockException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 25, multiplier = 2.0, random = true))
    @Transactional
    public LedgerEntry debit(String walletId, Caller caller, Money amount, String requestId) {
        caller.requireScope(Scopes.WRITE);
        WalletRow row = lockVisible(walletId, caller);

        // Inside the lock, so a retry racing the first attempt cannot slip between this check
        // and the insert below.
        Optional<LedgerEntryRow> replay = ledger.findByWalletIdAndRequestId(walletId, requestId);
        if (replay.isPresent()) {
            return replay.get().toDomain();
        }

        Wallet wallet = row.toDomain();
        Instant now = clock.instant();
        Posting posting = wallet.debit(amount, context(row, caller, requestId, now));
        return persist(row, posting, now);
    }

    // ---------------------------------------------------------------- shared with the other services

    /**
     * Loads a wallet the caller may see, WITHOUT a lock. Read paths only.
     *
     * <p>A wallet that exists but belongs to someone else returns the same 404, with the same
     * message, as one that does not exist. A 403 here would confirm the id is real and turn the
     * endpoint into a wallet-id oracle; the price is a slightly less helpful error for a
     * genuinely confused owner, and for a payments API that trade is the right way round.
     */
    public WalletRow requireVisible(String walletId, Caller caller) {
        return assertVisible(wallets.findById(walletId).orElseThrow(WalletException::walletNotFound), caller);
    }

    /**
     * Loads a wallet the caller may see, holding {@code SELECT ... FOR UPDATE} until commit.
     *
     * <p>There is deliberately no unlocked read before this one. Loading the row first would put
     * a copy in the persistence context, and whether the subsequent locked query refreshes that
     * copy is a Hibernate detail nobody should be betting a balance on.
     */
    public WalletRow lockVisible(String walletId, Caller caller) {
        return assertVisible(wallets.findForUpdate(walletId).orElseThrow(WalletException::walletNotFound), caller);
    }

    /**
     * Locks an existing wallet without checking visibility.
     *
     * <p>Needed by the multi-wallet operations: the lock ORDER is dictated by the wallet ids, so
     * the locks must be taken before it is known which of them the caller has to own. The
     * ownership check follows immediately, inside the same transaction, before anything is read
     * or written - see {@code TransferService.transfer}.
     */
    public WalletRow lockAnyExisting(String walletId) {
        return wallets.findForUpdate(walletId).orElseThrow(WalletException::walletNotFound);
    }

    public WalletRow assertVisible(WalletRow row, Caller caller) {
        if (row.userId().equals(caller.userId())) {
            return row;
        }
        if (!caller.isAdmin()) {
            throw WalletException.walletNotFound();
        }
        // Every cross-owner access is logged. An admin scope that leaves no trace is how a
        // support tool becomes an insider-risk tool.
        log.warn("admin {} accessed wallet {} owned by {}", caller.userId(), row.walletId(), row.userId());
        return row;
    }

    /** USER when acting on your own wallet, OPS when an operator acts on someone else's. */
    public InitiatorType initiatorFor(WalletRow row, Caller caller) {
        return row.userId().equals(caller.userId()) ? InitiatorType.USER : InitiatorType.OPS;
    }

    public PostingContext context(WalletRow row, Caller caller, String requestId, Instant now) {
        return PostingContext.standalone(ids.newId(), requestId, initiatorFor(row, caller),
                caller.userId(), now);
    }

    /**
     * Writes one posting: the ledger row first, then the new balance.
     *
     * <p>Both statements commit together, so within one transaction the order is not what makes
     * this safe. It is stated anyway because it is the order that survives the transaction being
     * split later: an entry with no balance update is detectable and repairable by recomputing
     * the fold, while a balance update with no entry is money that came from nowhere and can
     * never be explained.
     *
     * <p>{@code row} is a managed entity, so {@code applyBalance} is picked up by Hibernate's
     * dirty checking at flush - there is no {@code save} call to forget.
     */
    public LedgerEntry persist(WalletRow row, Posting posting, Instant now) {
        ledger.save(LedgerEntryRow.of(posting.entry()));
        row.applyBalance(posting.balanceAfter(), now);
        return posting.entry();
    }
}
