package com.slice.wallet.common;

/**
 * Supplies entity identifiers.
 *
 * <p>Injected rather than calling {@code UUID.randomUUID()} inline, for the same reason the
 * {@link java.time.Clock} is injected: a service that reaches for a static source of
 * nondeterminism can only be asserted on with a regex. A test can substitute a counter and then
 * assert on exact ids.
 */
@FunctionalInterface
public interface IdGenerator {

    String newId();
}
