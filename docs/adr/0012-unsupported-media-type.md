# 0012. Reject non-JSON request bodies with 415

## Status

Accepted, 2026-10-21. Proposed during review; the owner can switch it to 400 without other changes.

## Context

API Gateway REST APIs validate a request body only against the model mapped to the request's `Content-Type`. A `POST /person` sent as `text/plain` with a JSON body skips the JSON Schema model (§5.3, layer 1) entirely and reaches the Lambda unvalidated. The brief's response list (§5.2) does not cover this case.

## Decision

`CreatePersonHandler` accepts only `application/json`, case-insensitive and ignoring parameters such as `;charset=utf-8`. Any other or missing `Content-Type` returns `415 Unsupported Media Type` with problem type `/problems/unsupported-media-type`, before the body is read. See `service/src/main/java/com/tikkie/person/adapter/in/api/CreatePersonHandler.java` and `ProblemDetails.java`.

## Consequences

- The schema bypass is closed in the one place every request passes through.
- Clients get the standard status for this error, distinct from a malformed body.
- The API contract gains one status code beyond §5.2.
- Phase 4 still maps the request model to `application/json`. The handler check is the backstop, not the only check.

## Rejected alternatives

- **400 malformed request.** It works, but it conflates "wrong media type" with "bad JSON".
- **Relying on API Gateway alone.** That leaves the bypass described above open.
- **A catch-all model mapping in API Gateway.** It validates bodies that are not JSON against a JSON schema, which is confusing and still needs the handler check.
