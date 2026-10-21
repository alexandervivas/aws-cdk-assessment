# 0010. Request fingerprint behind a port, SHA-256 for the MVP

Status: Accepted, 2026-10-21, decided with the owner.

## Context

An idempotency key item stores a fingerprint of the request for 24 hours. A replay with the same payload returns the original response, and a different payload returns 422.

The fingerprint covers name, phone number and address. That input is low-entropy personal data, so anyone who obtains the IdempotencyKeys table could brute-force a plain hash offline and confirm who was registered.

## Decision

- `CanonicalRequest` (`application/CanonicalRequest.java`) builds the input: tag `v1` plus the normalised values in fixed order, joined with U+001F, strict UTF-8, masked `toString`.
- `RequestFingerprinter` (`application/RequestFingerprinter.java`) is the port.
- `Sha256RequestFingerprinter` (`application/Sha256RequestFingerprinter.java`) is the implementation. It computes SHA-256 in process with the JDK and returns 64 lowercase hex characters.

## Consequences

- We accept the brute-force risk for the MVP. It is limited by the 24-hour TTL on key items, KMS encryption at rest on the table (phase 4) and least-privilege IAM on the table.
- The create path has no extra network call, no per-request cost and no extra failure point.
- Moving to an HMAC later means one new adapter class behind the port and a new version tag in `CanonicalRequest`.

## Rejected for the MVP

- HMAC-SHA256 with KMS `GenerateMac`: one KMS call per create, which adds cost, tens of milliseconds of latency and a new failure point on the create path.
- HMAC with a secret in Secrets Manager: a secret to fetch, cache, rotate and keep out of SnapStart snapshots.

Both stay on the roadmap as the way to harden the fingerprint.
