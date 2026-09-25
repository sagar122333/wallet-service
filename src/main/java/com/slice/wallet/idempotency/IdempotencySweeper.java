package com.slice.wallet.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Evicts expired idempotency records.
 *
 * <p>Without this the table grows forever: keys arrive on every mutating request and nothing
 * removes them. The TTL is the window in which a client may still retry - 24 hours is the usual
 * industry choice, and it is configurable rather than hard-coded because the right answer depends
 * on how long the callers' own retry queues hold a message.
 *
 * <p>In a multi-instance deployment every instance would run this, which is harmless (the delete
 * is idempotent) but wasteful; the production answer is a shared scheduler lock such as
 * ShedLock, or moving the records to a store with native TTL like Redis. Named here rather than
 * built, because the seam matters more than the plumbing.
 */
@Component
public class IdempotencySweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencySweeper.class);

    private final IdempotencyRepository repository;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencySweeper(IdempotencyRepository repository,
                              Clock clock,
                              @Value("${wallet.idempotency.ttl:PT24H}") Duration ttl) {
        this.repository = repository;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Scheduled(fixedDelayString = "${wallet.idempotency.sweep-interval-ms:900000}")
    @Transactional
    public void sweep() {
        int removed = repository.deleteCreatedBefore(clock.instant().minus(ttl));
        if (removed > 0) {
            log.info("swept {} expired idempotency records older than {}", removed, ttl);
        }
    }
}
