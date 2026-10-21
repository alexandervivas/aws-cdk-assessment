# 0009. Idempotency keys are scoped per caller

Status: Accepted, 2026-10-21, decided with the owner.

## Context

`POST /persons` takes an `Idempotency-Key` header (docs/plan.md §2 decision 4). If the key were global, two callers that share a key value, for example a client that hard-codes one, would interfere for 24 hours. With a different payload the second caller gets a 422 for a request it never repeated. With an identical payload it receives the first caller's `personId`.

API Gateway IAM auth always provides the caller's principal ARN, so a caller identity is available on every request.

## Decision

A key is unique per caller, not globally.

- `CallerId` (`service/src/main/java/com/tikkie/person/application/CallerId.java`) wraps the principal ARN. `CallerId.of` rewrites an STS assumed-role ARN `arn:aws:sts::<account>:assumed-role/<role>/<session>` to `arn:aws:sts::<account>:assumed-role/<role>`. Any other non-blank ARN is kept as is. Null or blank throws `IllegalArgumentException`.
- The session name is stripped because a retry can arrive from a new STS session of the same role (credentials expired, a new process). Scoping by full session would turn that legitimate retry into a new create. Stripping it makes the retry replay.
- `PersonRepository.create` takes the `CallerId` next to the key. The IdempotencyKeys partition key value is `<callerId>|<idempotencyKey>` (`PersonItemMapper.java`). The attribute name stays `idempotencyKey`. The item also stores `callerId`.
- `CallerId` rejects `|`, so a caller and a key cannot be joined ambiguously. A UUID key cannot contain it either.
- `CallerId.toString()` is masked.

## Consequences

- Two callers using the same key and payload create two persons and get two key items.
- The same role from two sessions replays.
- Every caller of the service must come through IAM auth. A missing principal is a configuration error and is treated as a server error by the handler (phase 3).
- Role-level scope means two sessions of one role share keys. That is intended: they are the same caller for our purposes.
- Distinct IAM users or roles never share keys, even if they belong to one team.

## Rejected alternatives

- Global keys: cross-caller 422s and leaking another caller's `personId`, as described above.
- Scope by full session ARN: a retry from a new session would create a duplicate person, which is the failure idempotency exists to prevent.
- Scope by account: too coarse, callers in one account would still collide.
