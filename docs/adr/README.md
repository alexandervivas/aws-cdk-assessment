# Architecture decision records

Format: Status, Context, Decision, Consequences, Rejected alternatives. Decisions 0001-0008 come from docs/plan.md §2. Decisions 0009-0015 were taken during the build and amend the brief (docs/plan.md §13).

| ADR | Decision |
|-----|----------|
| [0001](0001-outbox-via-streams-and-pipes.md) | The event comes from the DynamoDB stream through an EventBridge Pipe, so there is no dual write. |
| [0002](0002-thin-event.md) | `PersonCreated` carries the person id and metadata, no PII. |
| [0003](0003-synchronous-created.md) | `POST /person` answers `201` synchronously. |
| [0004](0004-idempotency-key-and-transaction.md) | Idempotency by `Idempotency-Key` header and one DynamoDB transaction. |
| [0005](0005-rest-api.md) | REST API (v1) for request validation, IAM auth and throttling. |
| [0006](0006-iam-authentication.md) | SigV4 IAM authentication for service callers. |
| [0007](0007-one-stage-per-environment.md) | One CDK Stage per environment, all differences in `EnvironmentConfig`. |
| [0008](0008-java-and-snapstart.md) | Java 25 with SnapStart behind a `live` alias. |
| [0009](0009-per-caller-idempotency-scope.md) | Idempotency keys are scoped per caller (IAM principal). |
| [0010](0010-request-fingerprint.md) | Request fingerprint is SHA-256 behind a port, HMAC on the roadmap. |
| [0011](0011-idempotent-write-semantics.md) | Cancellation reasons, retries, 24 h window and request token for the write. |
| [0012](0012-unsupported-media-type.md) | Non-JSON content types get 415. |
| [0013](0013-iam-wildcard-exceptions.md) | The few IAM wildcards that cannot be avoided, enforced by a test. |
| [0014](0014-account-level-apigateway-logging-role.md) | The stack sets the account-level API Gateway logging role. Use a dedicated account. |
| [0015](0015-cdk-nag-acknowledged-findings.md) | cdk-nag gates synthesis; each acknowledged finding cites an ADR. No WAF, no execution logs. |
| [0016](0016-e2e-support-stack.md) | Separate, opt-in stack for end-to-end tests |
