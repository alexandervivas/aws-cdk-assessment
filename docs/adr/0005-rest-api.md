# 0005. REST API (v1) instead of HTTP API

Status: Accepted, 2026-10-21. Refined by [0012](0012-unsupported-media-type.md).

## Context

The contract rejects malformed bodies and missing headers before the Lambda runs, requires IAM auth, and needs throttling per stage.

## Decision

`PersonApi` (`infra/.../constructs/PersonApi.java`) builds a regional REST API with:

- a JSON Schema model for `application/json` and a validator for body and parameters (`PersonRequestModel.java`), with `additionalProperties: false` and max lengths;
- the `Idempotency-Key` header marked required;
- stage throttling from `EnvironmentConfig`, X-Ray tracing and access logs without bodies;
- a Lambda proxy integration on the `live` alias.

The model applies only to the mapped content type. The handler therefore returns 415 for anything else (0012).

## Consequences

- Schema violations are answered by API Gateway with its own 400 body, not the service's problem-details body. The handler answers domain violations with problem details.
- The `Idempotency-Key` header is not a required gateway parameter. The handler enforces its presence and format with a case-insensitive header lookup and problem details. There are conflicting claims about whether REST API required-header matching is case-sensitive, and the handler check is correct either way (review of PR #24).
- Domain value objects stay the authority on validity. The model is a first filter.
- REST API costs more per request than an HTTP API.
- Access logging needs an account-level role, see [0014](0014-account-level-apigateway-logging-role.md).

## Rejected alternatives

- HTTP API: cheaper but has no request body validation, so the schema check would move into the Lambda.
