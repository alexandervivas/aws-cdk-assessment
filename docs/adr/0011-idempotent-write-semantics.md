# 0011. Idempotent write semantics

Status: Accepted, 2026-10-21, decided with the owner.

## Context

Creating a person writes the person and an idempotency key item. They must succeed or fail together, and concurrent duplicates must create one person. `TransactWriteItems` can be cancelled for different reasons: some mean "the key is taken", some are transient, some will never succeed. Treating them alike either loses retries or hides bugs.

## Decision

`DynamoDbPersonRepository` (`adapter/out/dynamodb/DynamoDbPersonRepository.java`) does one `TransactWriteItems` with a person put (`attribute_not_exists(personId)`) and a key put. It branches on the cancellation reasons (index 0 person, index 1 key):

- Key put `ConditionalCheckFailed`: consistent `GetItem` on the key, then replay (same fingerprint) or conflict (different fingerprint, 422).
- `TransactionConflict`, `ThrottlingError`, `ProvisionedThroughputExceeded`, `RequestLimitExceeded`: retry after a jittered doubling backoff (20 to 80 ms first), 3 attempts in total. If the key has appeared by then, use it.
- `ValidationError`, a personId collision, or any unknown reason: fail fast.
- Exhausted or fail-fast cases throw `IllegalStateException` whose message carries reason codes only. The SDK exception is the cause. No request data is in the message.

Other decisions in the same class:

- Deterministic 24 h window. The key item stores `expiresAt` (`PersonItemMapper.KEY_TIME_TO_LIVE`). The key put condition is `attribute_not_exists(idempotencyKey) OR expiresAt <= :now`, and a read treats an expired item as absent. DynamoDB TTL deletion can lag by up to about 48 hours, so without this an expired key would keep answering for two days. The `Clock` is injected.
- `ClientRequestToken` is the candidate `personId`. If the response to a committed transaction is lost and the SDK retries, DynamoDB treats it as the same request and reports success, so the caller sees `Inserted`. If instead a resolved key holds our own candidate id, that also maps to `Inserted`.
- Persons is never read. The key item stores `personId` and `createdAt`, so a replay is built from it and the Lambda keeps write-only access to Persons.

## Consequences

- Transient contention is absorbed; permanent errors surface at once as 500 with a safe message.
- Worst case adds roughly 20 to 80 ms plus 40 to 160 ms of sleep before failing.
- An expired key may be overwritten even if its item still exists.
- Clock skew at the 24 h boundary is accepted. Lambda clocks are NTP-synced, so they differ by less than a second. In that window two concurrent identical requests can still create two persons: one Lambda sees the key as live and replays, the other sees it as expired and overwrites it.
- `expiresAt` is stored in whole seconds, so the effective window is 24 h minus up to 999 ms.
- When transient retries are exhausted, the repository does one final consistent read of the key before failing. A burst of identical requests then replays instead of returning 500s.
- Testing limit: DynamoDB Local serialises transactions, so the loser of a race always sees `ConditionalCheckFailed` on the key and never `TransactionConflict`. `DynamoDbConcurrentDuplicatesTest` proves one person is created. The conflict, throttling and lost-response paths are proven with scripted tests that intercept the client, not with real contention.

## Rejected alternatives

- Retry once without backoff: concurrent writers collide again in lockstep.
- Treat any present key as authoritative: an expired item would answer after 24 h, for up to about 48 h.
- No request token: a lost response followed by an SDK retry would look like a replay of someone else's request.
