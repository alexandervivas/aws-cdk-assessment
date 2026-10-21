# 0002. Thin PersonCreated event

Status: Accepted, 2026-10-21.

## Context

Other teams subscribe to `PersonCreated`. A name, phone number and address are personal data. Copying them into an event sends them to every subscriber and into the bus archive, where erasure would be hard.

## Decision

The event carries the person's id and metadata only. The Pipe input template (`PersonEvents.INPUT_TEMPLATE`) reads exactly five stream fields:

- `metadata.eventId` from the stream record `eventID`
- `metadata.eventVersion`, the fixed string `1`
- `metadata.occurredAt` from `createdAt`
- `metadata.correlationId`
- `data.personId`

Source is `tikkie.person-service` and detail-type is `PersonCreated`. A test pins the template and checks that it reads no other field. The JSON Schema for the event is [`docs/events/person-created.v1.json`](../events/person-created.v1.json), pinned by a contract test.

## Consequences

- PII stays in the Persons table. Erasure there does not need to chase copies.
- Consumers that need person data must call the service, which has no read endpoint yet ([roadmap](../roadmap.md)).
- Delivery is at least once, so consumers deduplicate on `eventId`.
- Adding a field is a new `eventVersion` and a new schema file.

## Rejected alternatives

- Fat event with the full person (event-carried state transfer): leaks PII to every subscriber and to the archive.
- Hashed or partial PII in the event: still personal data, and still hard to erase.
