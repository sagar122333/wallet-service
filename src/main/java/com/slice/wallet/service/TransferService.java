package com.slice.wallet.service;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.IdGenerator;
import com.slice.wallet.common.WalletException;
import com.slice.wallet.domain.LedgerEntry;
import com.slice.wallet.domain.Money;
import com.slice.wallet.domain.Posting;
import com.slice.wallet.domain.PostingContext;
import com.slice.wallet.domain.Wallet;
import com.slice.wallet.persistence.LedgerEntryRepository;
import com.slice.wallet.persistence.LedgerEntryRow;
import com.slice.wallet.persistence.WalletRow;
import com.slice.wallet.security.Caller;
import com.slice.wallet.security.Scopes;

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
 * Moving money between two wallets.
 *
 * <p>Separate from {@link WalletService} because it is the only operation that holds two locks,
 * and the rule that makes it safe - a total order over those locks - is worth being able to
 * point at.
 */
@Service
public class TransferService {

    /** Both legs, sharing one transferId. */
    public record TransferResult(String transferId, LedgerEntry debitLeg, LedgerEntry creditLeg) {
    }

    private final WalletService walletService;
    private final LedgerEntryRepository ledger;
    private final Clock clock;
    private final IdGenerator ids;

    public TransferService(WalletService walletService, LedgerEntryRepository ledger,
                           Clock clock, IdGenerator ids) {
        this.walletService = walletService;
        this.ledger = ledger;
        this.clock = clock;
        this.ids = ids;
    }

    @Retryable(retryFor = {PessimisticLockingFailureException.class, CannotAcquireLockException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 25, multiplier = 2.0, random = true))
    @Transactional
    public TransferResult transfer(Caller caller, String fromWalletId, String toWalletId,
                                   Money amount, String requestId) {
        caller.requireScope(Scopes.WRITE);
        if (fromWalletId.equals(toWalletId)) {
            // Refused before any lock is taken. Named explicitly because the alternative is a
            // silent no-op on a reentrant lock, or a self-deadlock on a non-reentrant one.
            throw new WalletException(ErrorCode.SAME_WALLET, "source and destination are the same wallet");
        }

        // LOCK ORDERING BY ID. Two simultaneous transfers A->B and B->A would otherwise each
        // hold one row lock and wait for the other forever. Any total order works; the id is
        // the only one both threads can agree on without communicating.
        boolean fromIsFirst = fromWalletId.compareTo(toWalletId) < 0;
        String firstId = fromIsFirst ? fromWalletId : toWalletId;
        String secondId = fromIsFirst ? toWalletId : fromWalletId;

        WalletRow firstRow = walletService.lockAnyExisting(firstId);
        WalletRow secondRow = walletService.lockAnyExisting(secondId);

        WalletRow fromRow = fromIsFirst ? firstRow : secondRow;
        WalletRow toRow = fromIsFirst ? secondRow : firstRow;

        // Only the SOURCE must be owned by the caller. Paying somebody else is the entire point,
        // so the destination merely has to exist.
        walletService.assertVisible(fromRow, caller);

        if (fromRow.currency() != toRow.currency()) {
            throw new WalletException(ErrorCode.CURRENCY_MISMATCH,
                    "cannot transfer between " + fromRow.currency() + " and " + toRow.currency()
                            + "; conversion is a separate operation with its own rate and audit trail");
        }

        Optional<TransferResult> replay = replayOf(fromWalletId, requestId);
        if (replay.isPresent()) {
            return replay.get();
        }

        Wallet fromWallet = fromRow.toDomain();
        Wallet toWallet = toRow.toDomain();
        Instant now = clock.instant();
        String transferId = ids.newId();

        // Both legs carry the CALLER'S OWN requestId, unmodified. That is possible only because
        // the ledger's unique constraint is (wallet_id, request_id) rather than request_id
        // alone: the legs live on different wallets, so they cannot collide. A global
        // constraint would force a synthetic suffix on one leg, and a suffix is how a key
        // overflows its column.
        PostingContext debitContext = PostingContext
                .standalone(ids.newId(), requestId, walletService.initiatorFor(fromRow, caller),
                        caller.userId(), now)
                .asTransferLeg(transferId);
        PostingContext creditContext = debitContext.withEntryId(ids.newId());

        // Both legs are validated before either is written. The transaction is what actually
        // makes the pair atomic; preparing first means the common failure - insufficient funds -
        // never depends on a rollback to undo a write that should not have happened.
        Posting out = fromWallet.debit(amount, debitContext);
        Posting in = toWallet.credit(amount, creditContext);

        LedgerEntry debitLeg = walletService.persist(fromRow, out, now);
        LedgerEntry creditLeg = walletService.persist(toRow, in, now);

        return new TransferResult(transferId, debitLeg, creditLeg);
    }

    @Transactional(readOnly = true)
    public TransferResult get(Caller caller, String transferId) {
        caller.requireScope(Scopes.READ);
        TransferResult result = assemble(transferId)
                .orElseThrow(() -> new WalletException(ErrorCode.TRANSFER_NOT_FOUND, "no such transfer"));
        // A transfer is visible to either party, so the caller must be able to see at least one
        // of its two wallets.
        boolean visible = canSee(caller, result.debitLeg().walletId())
                || canSee(caller, result.creditLeg().walletId());
        if (!visible) {
            throw new WalletException(ErrorCode.TRANSFER_NOT_FOUND, "no such transfer");
        }
        return result;
    }

    /**
     * The result of an earlier call under the same key, if there was one.
     *
     * <p>Looked up by the DEBIT leg, because that leg lives on the wallet the caller named and
     * so is the one their key is scoped to.
     */
    private Optional<TransferResult> replayOf(String fromWalletId, String requestId) {
        return ledger.findByWalletIdAndRequestId(fromWalletId, requestId)
                .map(LedgerEntryRow::transferId)
                .flatMap(this::assemble);
    }

    private Optional<TransferResult> assemble(String transferId) {
        if (transferId == null) {
            return Optional.empty();
        }
        List<LedgerEntry> originalLegs = ledger.findByTransferIdOrderByCreatedAtAsc(transferId).stream()
                .map(LedgerEntryRow::toDomain)
                // Compensating entries share the transferId so the pair stays findable; they are
                // not part of the original transfer.
                .filter(entry -> !entry.isReversal())
                .toList();
        if (originalLegs.size() != 2) {
            return Optional.empty();
        }
        LedgerEntry debit = originalLegs.stream().filter(e -> e.signedMinor() < 0).findFirst().orElse(null);
        LedgerEntry credit = originalLegs.stream().filter(e -> e.signedMinor() > 0).findFirst().orElse(null);
        if (debit == null || credit == null) {
            return Optional.empty();
        }
        return Optional.of(new TransferResult(transferId, debit, credit));
    }

    private boolean canSee(Caller caller, String walletId) {
        try {
            walletService.requireVisible(walletId, caller);
            return true;
        } catch (WalletException notVisible) {
            return false;
        }
    }
}
