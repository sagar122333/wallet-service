package com.slice.wallet.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRow, String> {

    /**
     * TTL eviction. Without it this table is an unbounded leak - keys arrive forever and nothing
     * removes them. A bulk delete rather than {@code deleteAll(findAll(...))}, which would load
     * every expired row into memory first.
     */
    @Modifying
    @Query("delete from IdempotencyRow r where r.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
