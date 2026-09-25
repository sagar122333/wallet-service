package com.slice.wallet.persistence;

import com.slice.wallet.domain.Currency;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Spring Data JPA access to {@code wallets}. */
public interface WalletRepository extends JpaRepository<WalletRow, String> {

    /**
     * Loads the wallet row and holds a {@code SELECT ... FOR UPDATE} lock on it until the
     * surrounding transaction ends.
     *
     * <p>Everything that follows - reading the balance, deciding whether the movement is
     * allowed, writing the new balance, inserting the ledger row - must happen inside that same
     * transaction. Split it across two and the lock is released before it protects anything.
     *
     * <p>There is deliberately no unlocked read of a wallet on any write path. A
     * {@code findById} before this call would put a stale copy in the persistence context, and
     * whether the locked query then refreshes that copy is a Hibernate detail nobody should be
     * betting a balance on.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletRow w where w.walletId = :walletId")
    Optional<WalletRow> findForUpdate(@Param("walletId") String walletId);

    /** Served by ix_wallets_user. */
    List<WalletRow> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Pre-check for the unique (user_id, currency) constraint, so the common case returns a
     * clean 409 instead of a constraint violation. The constraint is still what guarantees it -
     * this only improves the error message.
     */
    boolean existsByUserIdAndCurrency(String userId, Currency currency);
}
