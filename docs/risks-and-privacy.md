# Trade-offs, risks and privacy

## Trade-offs and accepted risks

| Risk | What we accepted and why | Where |
|------|--------------------------|-------|
| SHA-256 fingerprint | The fingerprint is a plain hash of low-entropy personal data. Someone with read access to the IdempotencyKeys table could brute-force it offline. Limited by the 24 h TTL, KMS encryption and least-privilege IAM. A KMS HMAC would add a call, cost and a failure point to every create, so it is a roadmap item and a one-class swap. | [0010](adr/0010-request-fingerprint.md) |
| Account-level logging role | The stack sets the API Gateway CloudWatch Logs role for the whole account and region, and retains it. Deploying into a shared account replaces other APIs' role. Deploy into a dedicated account. | [0014](adr/0014-account-level-apigateway-logging-role.md) |
| IAM wildcards | Three: X-Ray write actions on `*` (no resource-level support), the function's own log group with its `:*` stream suffix, and KMS `ReEncrypt*` and `GenerateDataKey*` on one key. A test fails on any other wildcard. `grant*` also gives more DynamoDB actions than the code uses. A fourth exception is the AWS managed policy on the API Gateway logging role. | [0013](adr/0013-iam-wildcard-exceptions.md), [0014](adr/0014-account-level-apigateway-logging-role.md) |
| Clock skew at 24 h | Lambda clocks differ by under a second. In that window two concurrent identical requests can both create a person: one sees the key as live, the other as expired. Stored `expiresAt` is whole seconds, so the window is 24 h minus up to 999 ms. | [0011](adr/0011-idempotent-write-semantics.md) |
| Conflict path testing | DynamoDB Local serialises transactions, so the race loser always sees `ConditionalCheckFailed`. The concurrent-duplicates test proves one person is created. Transaction conflict, throttling and lost-response paths are proven with scripted tests that intercept the client, not real contention. | [0011](adr/0011-idempotent-write-semantics.md) |
| Two error signals | The handler turns application failures into 500 responses, so the Lambda `Errors` metric does not count them. It emits its own `UnexpectedError` metric for those (`Outcome.java`). Lambda `Errors` still counts timeouts (10 s), init failures and runtime crashes, so monitoring covers both. |
| Cold start | SnapStart shortens cold starts but restore time remains. The Powertools `cold_start` log field is always false under SnapStart, so it cannot be used to count cold starts. | [0008](adr/0008-java-and-snapstart.md), `CreatePersonHandler.java` |
| At-least-once events | The Pipe delivers at least once. Consumers deduplicate on `metadata.eventId`. | [0001](adr/0001-outbox-via-streams-and-pipes.md) |

## Privacy and PII

- Personal fields are wrapped in `Sensitive<T>`, whose `toString()` returns `***` (`service/src/main/java/com/tikkie/person/domain/Sensitive.java`). Records that hold personal data mask their own `toString()`, and tests assert that none of the values appear.
- The handler logs the correlation ID, outcome and person id only. On failure it logs exception class names, never messages, because messages from below can quote input (`CreatePersonHandler.java`). Problem-detail text is fixed and never echoes input.
- `service/src/main/resources/log4j2.xml` holds the AWS SDK and HTTP client loggers at WARN even if Powertools changes log levels, because SDK request objects print attribute values.
- The event carries no PII ([ADR 0002](adr/0002-thin-event.md)); the Pipe template reads five stream fields, pinned by a test.
- API Gateway access logs hold request context only, never bodies (`PersonApi.ACCESS_LOG_FORMAT`). They do contain the caller's IAM ARN and source IP. Treat the log group as holding identities.
- Tables and the dead-letter queue are encrypted with a customer-managed KMS key with rotation.
- All sample data in tests is fictional.
