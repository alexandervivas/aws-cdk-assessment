package com.tikkie.person.application;

import com.tikkie.person.domain.Person;

public interface PersonRepository {

    /**
     * Idempotency keys are scoped per caller: the same {@code key} from two callers names two requests.
     * Claims {@code key} for {@code caller} and writes {@code person} in one atomic step. If that caller
     * already claimed the key, the adapter compares the stored request hash with {@code hash} itself: equal
     * returns {@code ReplayOf} with the stored {@code personId} and {@code createdAt} (never those of
     * {@code person}), different returns {@code Conflict}. Keys are honoured for exactly 24 hours after the
     * stored {@code createdAt}: an expired key counts as absent, so the request creates a new person. Failures
     * other than a cancelled transaction (network, access, service errors) propagate unchanged. A cancelled
     * transaction that exhausts its retries or fails fast surfaces as an {@link IllegalStateException} whose
     * message carries only the cancellation reason codes, never a value from the request or the items.
     */
    CreateOutcome create(Person person, CallerId caller, IdempotencyKey key, RequestHash hash, String correlationId);
}
