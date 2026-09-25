package com.slice.wallet.domain;

/**
 * Who moved the money - which is not the same as whose wallet it is.
 *
 * <p>This enum exists because the service has authentication. Once a request has a caller, every
 * ledger entry has an initiator, and an ops reversal, a settlement job and a customer spend are
 * three different kinds of actor writing to the same wallet. Recording only the wallet leaves
 * "who did this" unanswerable, which is the first question asked in any dispute.
 */
public enum InitiatorType {

    /** The wallet's own owner, acting on their own wallet. */
    USER,

    /** An operator or support agent acting on someone else's wallet, under an admin scope. */
    OPS,

    /** Another service: a settlement job, a webhook consumer, a scheduled sweep. */
    SERVICE
}
