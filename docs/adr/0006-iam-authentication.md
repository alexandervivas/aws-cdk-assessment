# 0006. IAM (SigV4) authentication

Status: Accepted, 2026-10-21. Related: [0009](0009-per-caller-idempotency-scope.md), [0013](0013-iam-wildcard-exceptions.md).

## Context

The callers are other services in the same landscape. Managing a secret per caller adds rotation work and leak risk.

## Decision

`POST /person` uses `AuthorizationType.IAM` (`PersonApi.java`). Callers sign requests with SigV4 and need `execute-api:Invoke` on the method. The Lambda reads the caller's principal ARN from the request context (`CreatePersonHandler.callerArn`) and uses it to scope idempotency keys (0009). A missing principal is treated as a deployment fault and answered with 500.

## Consequences

- No secrets to issue or rotate. Access is granted and audited with IAM.
- Callers need an AWS identity and a SigV4 client. Browsers and external parties cannot call the API directly.
- The access log records the caller's ARN and source IP (`PersonApi.ACCESS_LOG_FORMAT`).
- Per-caller rate limits are not available. Throttling is per stage. This is on the roadmap.
- The identity is only as fine as the IAM principal, so callers sharing a role share idempotency scope.

## Rejected alternatives

- API keys: they identify a caller but do not authenticate it, and they are a secret to manage.
- Cognito: built for end users, not service-to-service calls.
