package com.slice.wallet.service;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.IdGenerator;
import com.slice.wallet.common.WalletException;
import com.slice.wallet.domain.EntryType;
import com.slice.wallet.domain.LedgerEntry;
import com.slice.wallet.domain.Posting;
import com.slice.wallet.domain.PostingContext;
import com.slice.wallet.domain.Wallet;
import com.slice.wallet.persistence.LedgerEntryRepository;
import com.slice.wallet.persistence.LedgerEntryRow;
import com.slice.wallet.persistence.WalletRow;
import com.slice.wallet.security.Caller;
import com.slice.wallet.security.Scopes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Undoing a movement by posting its opposite.
 *
 * <p>The original entry is never touched. An append-only ledger has no correction, only
 * compensation, which is what keeps history replayable and disputes answerable.
 *
 * <p><b>Operators only, and a reason is mandatory.</b> A customer reversing their own spend is a
 * refund they granted themselves. This is the endpoint that gives the API a real 403.
 *
 * <p><b>"Reversed at most once" belongs to the ENTRY, not the wallet</b> - so a wallet row lock
 * cannot enforce it, and neither can the check below on its own. Two concurrent reversals under
 * different idempotency keys can both pass {@code existsByReversalOf} on separate connections.
 * What actually stops the second one is {@code uq_ledger_reversal_of}; the check is a fast path
 * that turns the common case into a clean 409 instead of a constraint violation, and the
 * {@code catch} at the bottom turns the uncommon case into the same 409.
 */
@Service
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    /** The compensating entries this reversal posted, in the order they were written. */
    public record ReversalResult(String reversedId, List<LedgerEntry> compensatingEntries) {
    }

    private final WalletService walletService;
    private final LedgerEntryRepository ledger;
    private final Clock clock;
    private final IdGenerator ids;

    public ReversalService(WalletService walletService, LedgerEntryRepository ledger,
                           Clock clock, IdGenerator ids) {
        this.walletService = walletService;
        this.ledger = ledger;
        this.clock = clock;
        this.ids = ids;
    }

    /**
     * Reverses a single, standalone movement - a top-up or a spend.
     *
     * <p>A leg of a transfer is refused here. Compensating one side of a two-sided movement
     * leaves each wallet's own ledger perfectly consistent while creating money overall, which
     * is the worst kind of bug: every per-wallet check still passes. Transfers are reversed as
     * transfers, through {@link #reverseTransfer}.
     */
    @Retryable(retryFor = {PessimisticLockingFailureException.class, CannotAcquireLockException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 25, multiplier = 2.0, random = true))
    @Transactional
    public ReversalResult reverseEntry(Caller caller, String entryId, String reason, String requestId) {
        caller.requireScope(Scopes.REVERSE);

        LedgerEntry original = ledger.findById(entryId)
                .map(LedgerEntryRow::toDomain)
                .orElseThrow(() -> new WalletException(ErrorCode.TRANSACTION_NOT_FOUND, "no such transaction"));

        if (original.isReversal()) {
            throw new WalletException(ErrorCode.CANNOT_REVERSE_A_REVERSAL,
                    "reverse the original transaction, not its compensating entry");
        }
        if (original.isTransferLeg()) {
            throw new WalletException(ErrorCode.REVERSE_TRANSFER_AS_TRANSFER,
                    "this transaction is one leg of transfer " + original.transferId()
                            + "; reverse the transfer instead");
        }
        return compensate(caller, entryId, reason, requestId, List.of(original));
    }

    /** Reverses both legs of a transfer, together, under both wallets' locks. */
    @Retryable(retryFor = {PessimisticLockingFailureException.class, CannotAcquireLockException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 25, multiplier = 2.0, random = true))
    @Transactional
    public ReversalResult reverseTransfer(Caller caller, String transferId, String reason,
                                          String requestId) {
        caller.requireScope(Scopes.REVERSE);

        List<LedgerEntry> legs = ledger.findByTransferIdOrderByCreatedAtAsc(transferId).stream()
                .map(LedgerEntryRow::toDomain)
                .filter(entry -> !entry.isReversal())
                .toList();
        if (legs.isEmpty()) {
            throw new WalletException(ErrorCode.TRANSFER_NOT_FOUND, "no such transfer");
        }
        return compensate(caller, transferId, reason, requestId, legs);
    }

    /**
     * Posts one compensating entry per original leg.
     *
     * <p>Every wallet involved is locked first, in ascending id order - the same total order a
     * transfer uses, so a reversal and a transfer touching the same pair cannot deadlock against
     * each other.
     */
    private ReversalResult compensate(Caller caller, String reversedId, String reason,
                                      String requestId, List<LedgerEntry> originals) {
        List<String> walletIds = originals.stream()
                .map(LedgerEntry::walletId)
                .distinct()
                .sorted()
                .toList();

        Map<String, WalletRow> locked = new LinkedHashMap<>();
        for (String walletId : walletIds) {
            locked.put(walletId, walletService.lockAnyExisting(walletId));
        }

        // Idempotent replay. Scoped to the first leg's wallet, which is where this key was
        // written last time; every compensating leg of one reversal carries the same key.
        Optional<LedgerEntryRow> replay =
                ledger.findByWalletIdAndRequestId(originals.get(0).walletId(), requestId);
        if (replay.isPresent()) {
            return new ReversalResult(reversedId, alreadyPosted(reversedId, originals));
        }

        for (LedgerEntry original : originals) {
            if (ledger.existsByReversalOf(original.entryId())) {
                throw new WalletException(ErrorCode.ALREADY_REVERSED,
                        "this transaction has already been reversed");
            }
        }

        Instant now = clock.instant();
        List<Posting> postings = new ArrayList<>(originals.size());

        // Prepare every leg before writing any of them, so an insufficient-funds failure on the
        // second leg posts nothing at all.
        Map<String, Wallet> wallets = new LinkedHashMap<>();
        for (LedgerEntry original : originals) {
            WalletRow row = locked.get(original.walletId());
            Wallet wallet = wallets.computeIfAbsent(original.walletId(), id -> row.toDomain());

            PostingContext context = PostingContext
                    .standalone(ids.newId(), requestId,
                            walletService.initiatorFor(row, caller), caller.userId(), now)
                    .asReversalOf(original.entryId(), reason);
            if (original.isTransferLeg()) {
                // Keep the transferId so the compensating pair stays findable alongside the
                // original pair.
                context = context.asTransferLeg(original.transferId());
            }

            // Reversing a CREDIT means taking money back out, which can legitimately fail: the
            // customer may already have spent it. Money is non-negative by construction, so an
            // overdraft is not expressible - which is the type system pointing out that a
            // credit line is a different concept, not a negative balance.
            postings.add(original.type() == EntryType.CREDIT
                    ? wallet.debit(original.amount(), context)
                    : wallet.credit(original.amount(), context));
        }

        List<LedgerEntry> written = new ArrayList<>(postings.size());
        try {
            for (Posting posting : postings) {
                written.add(walletService.persist(locked.get(posting.entry().walletId()), posting, now));
            }
            // Flush inside the try, so uq_ledger_reversal_of fires HERE rather than at commit,
            // where it would escape this catch and surface as a 500.
            ledger.flush();
        } catch (DataIntegrityViolationException raced) {
            // A concurrent reversal under a different key won. The constraint is the guarantee;
            // this turns it into the same 409 the fast path returns.
            throw new WalletException(ErrorCode.ALREADY_REVERSED,
                    "this transaction has already been reversed", raced);
        }

        log.info("reversal of {} by {} ({} legs): {}", reversedId, caller.userId(), written.size(), reason);
        return new ReversalResult(reversedId, written);
    }

    /** On a replay, the compensating entries that the first call wrote. */
    private List<LedgerEntry> alreadyPosted(String reversedId, List<LedgerEntry> originals) {
        List<LedgerEntry> found = new ArrayList<>(originals.size());
        for (LedgerEntry original : originals) {
            ledger.findByReversalOf(original.entryId())
                    .map(LedgerEntryRow::toDomain)
                    .ifPresent(found::add);
        }
        if (found.isEmpty()) {
            throw new WalletException(ErrorCode.INTERNAL_ERROR,
                    "idempotent replay of reversal " + reversedId + " found no compensating entries");
        }
        return found;
    }
}
