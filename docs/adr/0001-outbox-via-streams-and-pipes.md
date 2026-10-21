# 0001. Event derived from the DynamoDB stream through EventBridge Pipes

Status: Accepted, 2026-10-21.

## Context

Creating a person must persist it and emit `PersonCreated`. Doing both from the Lambda is a dual write: if the process fails between them, a person exists without an event, or an event exists for a person that was never stored.

## Decision

The table write is the only write. The Persons table has a stream with `NEW_IMAGE` (`infra/.../constructs/PersonStorage.java`). An EventBridge Pipe reads it, keeps `INSERT` records only, rewrites each into a thin event with an input template and puts it on the bus `person-events-{stage}` (`infra/.../constructs/PersonEvents.java`). The Lambda has no `PutEvents` permission and no publishing code.

- The pipe starts at `LATEST`, uses batch size 1 and retries a record up to 10 times.
- A record that still fails goes to a KMS-encrypted SQS dead-letter queue, kept 14 days.
- Idempotent writes (ADR [0011](0011-idempotent-write-semantics.md)) keep a replayed request out of the stream, so a replay never produces a second event.

## Consequences

- Every committed insert is followed by an event, at least once. Consumers must deduplicate on `metadata.eventId`.
- Latency from write to event is higher than a direct `PutEvents`, by the stream and Pipe delay.
- A failing event is visible in the dead-letter queue instead of being lost in the Lambda.
- `UPDATE` and `REMOVE` records exist in the stream but are filtered out. Adding more event types means new filters and templates.
- DLQ messages hold batch metadata, not the record, so replay needs the stream sequence numbers.

## Rejected alternatives

- Lambda writes to DynamoDB then calls `PutEvents`: partial failure leaves a person without an event.
- A hand-written outbox table drained by a Lambda: more code and one more component to operate for the same guarantee.
- `PutEvents` and the table write in one Lambda with compensation: compensation can fail too.
