# 0004. Idempotency with a header key and a DynamoDB transaction

Status: Accepted, 2026-10-21. Refined by [0009](0009-per-caller-idempotency-scope.md), [0010](0010-request-fingerprint.md) and [0011](0011-idempotent-write-semantics.md).

## Context

Clients retry after timeouts. A retry must not create a second person or a second event. Two genuine creations of identical people are possible, so the payload alone cannot identify a retry.

## Decision

- The caller sends `Idempotency-Key`, a UUID. The handler alone checks presence and format, with a case-insensitive header lookup, and answers 400 problem details otherwise (ADR [0005](0005-rest-api.md)) (`application/IdempotencyKey.java`).
- One `TransactWriteItems` writes the person and an IdempotencyKeys item, each with a condition, so both exist or neither does.
- A key seen again with the same request fingerprint replays the original `201` with `Idempotent-Replayed: true`. With a different fingerprint the answer is `422`.
- Keys expire after 24 h (`expiresAt`, DynamoDB TTL, plus a read-time check).
- Refinements: keys are scoped per caller (0009), the fingerprint is SHA-256 over a canonical form behind a port (0010), and cancellation reasons, retries, the exact 24 h window and `ClientRequestToken` are in 0011.

## Consequences

- A duplicate never reaches the stream, so no duplicate events.
- Every create costs two writes in one transaction.
- Callers must generate and persist a key per logical create.
- Known limits are listed in 0010 (brute-force risk of a plain hash) and 0011 (clock skew at the 24 h boundary).

## Rejected alternatives

- Powertools idempotency module: needs AspectJ weaving, and the behaviour is easier to explain in plain code.
- Payload hashing as the only key: two genuine identical creations are indistinguishable from a retry.
