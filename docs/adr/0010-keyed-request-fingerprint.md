# 0010. Keyed request fingerprint (HMAC-SHA256 via KMS)

Status: Accepted, 2026-10-21, decided with the owner.

## Context

An idempotency key item stores a fingerprint of the request so a replay with the same payload returns the original response and a different payload returns 422. The first design stored an unsalted SHA-256 of the request (docs/plan.md §5.4 as originally written).

The input is name, phone number and address. That is low-entropy personal data. Anyone who obtains the IdempotencyKeys table could test guesses offline and confirm who was registered. That contradicts the brief's PII and GDPR stance.

## Decision

The fingerprint is HMAC-SHA256 computed by a KMS HMAC key.

- `CanonicalRequest` (`application/CanonicalRequest.java`) builds the string to authenticate: tag `v1` plus the normalised values in fixed order, joined with U+001F, strict UTF-8, masked `toString`.
- `RequestFingerprinter` (`application/RequestFingerprinter.java`) is the port. `KmsRequestFingerprinter` (`adapter/out/kms/KmsRequestFingerprinter.java`) calls `GenerateMac` with `HMAC_SHA_256` and returns 64 lowercase hex characters. It rejects a result that is not 32 bytes. KMS errors propagate and the handler maps them to 500.
- The key never leaves KMS. The service holds no secret, so nothing secret is captured in a SnapStart snapshot.
- The Lambda is granted `kms:GenerateMac` only. The KMS key (`HMAC_256`, `GENERATE_VERIFY_MAC`) and the grant are infrastructure for phase 4.
- Rotation: HMAC keys have no automatic rotation. To rotate, create a new key, point `REQUEST_FINGERPRINT_KEY_ID` at it and bump the version tag in `CanonicalRequest` (`v1` to `v2`). Fingerprints made with the old key expire with their key items within 24 hours. A retry that spans the switch sees a different fingerprint and gets a 422, so rotate in a quiet period.

## Consequences

- A leaked key table no longer allows offline guessing without calling KMS.
- Each create makes one extra KMS call before the DynamoDB transaction. This adds latency of the order of tens of milliseconds and a per-request KMS charge. Replays that fail early on the key still pay it, because the fingerprint is needed to compare.
- KMS availability is now on the create path.
- Unit tests use a stub `KmsClient` and an in-test HMAC fixture, so no test needs KMS.

## Rejected alternatives

- Plain SHA-256: brute-forceable, as described above.
- HMAC secret in Secrets Manager or an environment variable: the secret would sit in Lambda memory and in SnapStart snapshots, and needs its own rotation and access control.
- A salted hash stored next to the salt: an attacker with the table has the salt, so the cost of guessing is unchanged.
