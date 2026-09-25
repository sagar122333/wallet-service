package com.slice.wallet.persistence;

import com.slice.wallet.domain.EntryType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data JPA access to {@code ledger_entries}. Inserts and reads only - never updates. */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryRow, String> {

    /**
     * The idempotency lookup, scoped to the wallet because that is how the unique constraint is
     * scoped. A global lookup by {@code requestId} alone would make one caller's key visible to
     * every other caller.
     */
    Optional<LedgerEntryRow> findByWalletIdAndRequestId(String walletId, String requestId);

    /** Both legs of a transfer, plus any compensating legs that share its id. */
    List<LedgerEntryRow> findByTransferIdOrderByCreatedAtAsc(String transferId);

    /**
     * Whether this entry has already been compensated.
     *
     * <p>Only ever called while holding the relevant wallet row locks - and even then it is a
     * fast path, not the guarantee. Two concurrent reversals under different idempotency keys
     * can both pass this check on separate connections; what actually stops the second one is
     * {@code uq_ledger_reversal_of}. The check exists to turn the common case into a clean 409
     * instead of a constraint violation.
     */
    boolean existsByReversalOf(String reversalOf);

    /** The compensating entry for this one, if it has been reversed. Unique by constraint. */
    Optional<LedgerEntryRow> findByReversalOf(String reversalOf);

    /**
     * One page of a wallet's history, newest first, using keyset (not offset) pagination.
     *
     * <p>{@code beforeAt}/{@code beforeId} are the composite sort key of the last row the client
     * saw, so the predicate reads "strictly older than that row". With {@code OFFSET} instead, a
     * credit arriving between two page requests shifts every row down and the client sees one
     * entry twice and misses another; offset is also O(offset) in MySQL, so deep pages get
     * slower without bound. This query is an index range scan over
     * {@code ix_ledger_wallet_created}.
     *
     * <p>Pass {@code PageRequest.of(0, limit + 1)}: the extra row answers "is there another
     * page?" without a second {@code COUNT} query.
     */
    @Query("""
           select e from LedgerEntryRow e
           where e.walletId = :walletId
             and (:beforeAt is null
                  or e.createdAt < :beforeAt
                  or (e.createdAt = :beforeAt and e.entryId < :beforeId))
           order by e.createdAt desc, e.entryId desc
           """)
    List<LedgerEntryRow> findPage(@Param("walletId") String walletId,
                                  @Param("beforeAt") Instant beforeAt,
                                  @Param("beforeId") String beforeId,
                                  Pageable pageable);

    /**
     * The signed fold over a wallet's ledger. Must always equal the cached balance; the
     * reconciliation endpoint asserts exactly that.
     *
     * <p>The {@code CREDIT} literal arrives as a parameter rather than being written into the
     * JPQL, because a fully-qualified enum constant inside a query string is one refactor away
     * from a runtime failure the compiler cannot see.
     */
    @Query("""
           select coalesce(sum(case when e.entryType = :credit
                                    then e.amountMinor else -e.amountMinor end), 0)
           from LedgerEntryRow e
           where e.walletId = :walletId
           """)
    long sumSignedMinor(@Param("walletId") String walletId, @Param("credit") EntryType credit);

    long countByWalletId(String walletId);
}
