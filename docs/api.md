# API contract

Request: headers `Content-Type: application/json` and `Idempotency-Key: <UUID>` (required), optional `X-Correlation-Id`. Body: `firstName`, `lastName`, `phoneNumber`, and `address` with `street`, `houseNumber`, `postalCode`, `city`, `countryCode` and an optional `houseNumberAddition`. Schema: `infra/src/main/java/com/tikkie/person/infra/constructs/PersonRequestModel.java`. Example body and rules: `docs/plan.md` §5.1 and §5.3.

| Status | When | Problem type |
|--------|------|--------------|
| 201 | Created. `Location: /person/{personId}`, body `personId` and `createdAt`. | none |
| 201 | Replay of the same key and the same request. Same body, header `Idempotent-Replayed: true`. | none |
| 400 | Domain validation failed, with a list of field errors, or the body is unreadable, or the `Idempotency-Key` header is missing or not a UUID (the handler checks it, whatever the header's casing). | `/problems/validation-error`, `/problems/malformed-request` |
| 400 | API Gateway rejected the body against the schema. This body comes from API Gateway, not the service. | none |
| 415 | `Content-Type` is not `application/json` ([ADR 0012](adr/0012-unsupported-media-type.md)). | `/problems/unsupported-media-type` |
| 422 | Same key used with a different request. | `/problems/idempotency-key-reused` |
| 500 | Unexpected failure. Body has the correlation ID, no stack trace and no input. | `/problems/internal-error` |

Problem bodies follow RFC 9457 and are built in `service/src/main/java/com/tikkie/person/adapter/in/api/ProblemDetails.java`. Every response from the handler carries `X-Correlation-Id`. Responses that API Gateway returns itself (IAM rejection, schema validation, throttling) don't carry it.
