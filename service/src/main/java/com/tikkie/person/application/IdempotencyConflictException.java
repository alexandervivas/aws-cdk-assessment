package com.tikkie.person.application;

public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("idempotency key was already used with a different request");
    }
}
