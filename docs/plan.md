# Person Service — Implementation Plan

Brief for building the Tikkie backend assessment: a serverless **Person Service** on AWS, written in **Java**, with infrastructure in **AWS CDK (Java)**. Read this whole file before writing code. Work phase by phase; each phase ends with `mvn verify` green and a commit.

---

## 1. Goal and scope

Build a microservice that exposes `POST /person` to create a person. On creation it must:

1. Persist first name, last name, phone number and address in **DynamoDB**.
2. Emit a **`PersonCreated`** event into the microservice landscape (an **EventBridge** custom bus).

Non-functional requirements from the case:

- Deployable **multiple times** (at least `dev` and `prod`) from the same code.
- **Serverless**: scales with usage, no servers to manage.

Out of scope (mention as "next steps" in the README only): `GET /person/{id}`, listing persons, update/delete, GDPR erasure flow.

---

## 2. Architecture

```
Client ──(SigV4/IAM)──▶ API Gateway (REST, JSON Schema validation)
                              │
                              ▼
                     Lambda: CreatePersonHandler (Java, SnapStart)
                              │  TransactWriteItems (person + idempotency key)
                              ▼
        ┌──────────── DynamoDB: Persons table ────────────┐
        │                                                 │   DynamoDB: IdempotencyKeys table (TTL)
        └──── DynamoDB Stream (NEW_IMAGE) ────────────────┘
                              │
                              ▼
          EventBridge Pipe (filter INSERT, transform → thin event, DLQ on failure)
                              │
                              ▼
                 EventBridge custom bus: person-events
                              │
                              ▼
                   Consumers' rules (other teams)
```

### Key design decisions (also write each one as an ADR in `docs/adr/`)

| # | Decision | Why | Rejected alternative |
|---|----------|-----|----------------------|
| 1 | **Event derived from the DynamoDB Stream via EventBridge Pipes** (transactional outbox via change data capture) | No dual write: the table write is the only write; the event is guaranteed to follow every committed insert. No publishing code. | Lambda writes to DynamoDB and then calls `PutEvents` — partial failure leaves a person without an event. |
| 2 | **Thin event** (`personId` + metadata, no PII) | PII never leaves the service; GDPR erasure stays possible; consumers decide what they need. | Fat event with full person (event-carried state transfer) — leaks PII to every subscriber. |
| 3 | **Synchronous `201 Created`** | Client gets the ID immediately and validation errors synchronously. | API → SQS → Lambda with `202` — only worth it if spikes exceed what DynamoDB can absorb. |
| 4 | **Idempotency via `Idempotency-Key` header + DynamoDB transaction** | Safe client retries; a duplicate never reaches the stream, so no duplicate events. Explicit and easy to explain. | Powertools idempotency (needs AspectJ weaving); payload hashing (two genuine identical creations are possible). |
| 5 | **REST API (v1) rather than HTTP API** | Request validation with JSON Schema models, IAM auth, usage plans and throttling per stage. | HTTP API — cheaper but no built-in request body validation. |
| 6 | **IAM (SigV4) auth** | Service-to-service in a microservice landscape; no secrets to manage. | API keys (identify, don't authenticate); Cognito (for end users, not services). |
| 7 | **One CDK `Stage` per environment, config in code** | Same template, different parameters; no hardcoded names, so stages can't collide. | Copy-pasted stacks per environment. |
| 8 | **Java + SnapStart** | Matches the requested language; SnapStart removes most of the JVM cold-start penalty. | Node/TypeScript Lambda. |

---

## 3. Tech stack

- **Java**: latest LTS that AWS Lambda supports as a managed runtime (at least 21). Same version for build and runtime.
- **Build**: Maven, multi-module.
- **Lambda libraries**: AWS SDK for Java v2 (`dynamodb`, `url-connection-client` or `aws-crt-client`), `aws-lambda-java-core`, `aws-lambda-java-events`, Jackson, `libphonenumber`, Powertools for AWS Lambda (Java) **logging, tracing and metrics only** (no idempotency module — see decision 4).
- **Infrastructure**: AWS CDK v2 for Java (`aws-cdk-lib`), `cdk-nag` for security rules.
- **Tests**: JUnit 5, AssertJ, Mockito, ArchUnit, Testcontainers (DynamoDB Local image), CDK `assertions` module, k6 for load.

---

## 4. Repository layout

```
person-service/
├── pom.xml                      # parent: versions, plugins, modules
├── cdk.json                     # CDK app entry (runs the infra module)
├── service/                     # the Lambda
│   └── src/main/java/com/tikkie/person/
│       ├── domain/              # pure Java, NO AWS imports (enforced by ArchUnit)
│       │   ├── Person.java
│       │   ├── PersonId.java
│       │   ├── PersonName.java          # first/last name value objects
│       │   ├── PhoneNumber.java         # parsed + normalised to E.164
│       │   ├── Address.java
│       │   ├── CountryCode.java         # ISO 3166-1 alpha-2
│       │   ├── Sensitive.java           # wrapper whose toString() is "***"
│       │   └── ValidationException.java # carries a list of field errors
│       ├── application/
│       │   ├── CreatePersonCommand.java
│       │   ├── CreatePersonUseCase.java
│       │   ├── CreatePersonResult.java  # created vs replayed
│       │   └── PersonRepository.java    # port
│       └── adapter/
│           ├── in/api/
│           │   ├── CreatePersonHandler.java   # Lambda entry point
│           │   ├── CreatePersonRequest.java   # JSON DTO
│           │   ├── PersonResponse.java
│           │   └── ProblemDetails.java        # RFC 9457 error body
│           └── out/dynamodb/
│               └── DynamoDbPersonRepository.java
├── infra/                       # CDK app
│   └── src/main/java/com/tikkie/person/infra/
│       ├── PersonServiceApp.java        # main(): creates Dev and Prod stages
│       ├── EnvironmentConfig.java       # per-env settings (record)
│       ├── PersonServiceStage.java
│       ├── PersonServiceStack.java
│       └── constructs/
│           ├── PersonStorage.java       # tables, KMS key
│           ├── PersonApi.java           # API Gateway, models, validator, Lambda
│           ├── PersonEvents.java        # bus, pipe, DLQ, archive
│           └── PersonMonitoring.java    # alarms
├── e2e/                         # tests against a deployed stack (Maven profile, not in default build)
├── load/k6/create-person.js
├── docs/adr/                    # 0001-outbox-via-streams-and-pipes.md, etc.
├── .github/workflows/ci.yml
└── README.md
```

---

## 5. Contracts

### 5.1 Request — `POST /person`

Headers: `Content-Type: application/json`, `Idempotency-Key: <UUID>` (**required**), optional `X-Correlation-Id`.

```json
{
  "firstName": "Anna",
  "lastName": "de Vries",
  "phoneNumber": "+31 6 12345678",
  "address": {
    "street": "Damrak",
    "houseNumber": "1",
    "houseNumberAddition": "III",
    "postalCode": "1012 LG",
    "city": "Amsterdam",
    "countryCode": "NL"
  }
}
```

### 5.2 Responses

- `201 Created`, header `Location: /person/{personId}`, body: `{ "personId": "...", "createdAt": "..." }`.
- **Replay** of the same `Idempotency-Key` with the same payload → same `201` and same body (header `Idempotent-Replayed: true`).
- Same key with a **different** payload → `422` problem details.
- Schema violations → `400` from API Gateway; domain violations → `400` problem details listing each field error.
- Unexpected failure → `500` problem details with the correlation ID, no stack trace, no PII.

### 5.3 Validation rules

Two layers:

1. **API Gateway JSON Schema model**: required fields, string types, max lengths (names 100, street 200, city 100, postal code 20, house number 10, addition 10), `countryCode` pattern `^[A-Z]{2}$`, `additionalProperties: false`.
2. **Domain value objects** ("parse, don't validate"; constructors throw on invalid input):
   - Names: trimmed, non-blank, Unicode letters allowed, apostrophes, hyphens, spaces (O'Brien, Müller, van der Berg). Do **not** over-validate.
   - Phone: parsed with `libphonenumber`, using `countryCode` as the default region when no `+` prefix; must be a valid number; stored as **E.164** (`+31612345678`).
   - Address: `countryCode` is a real ISO 3166-1 alpha-2 code; postal code validated for `NL` (`^[1-9][0-9]{3} ?[A-Z]{2}$`, normalised to `1012 LG`), a generic non-blank check for other countries.

### 5.4 DynamoDB items

**Persons** table — partition key `personId` (String, UUID v4), on-demand billing, stream `NEW_IMAGE`:

| Attribute | Example |
|-----------|---------|
| `personId` | `3f1c…` |
| `firstName`, `lastName` | `Anna`, `de Vries` |
| `phoneNumber` | `+31612345678` |
| `address` | map with the fields above |
| `createdAt` | ISO-8601 UTC |
| `correlationId` | from header or generated |
| `schemaVersion` | `1` |

**IdempotencyKeys** table — partition key `idempotencyKey`, TTL attribute `expiresAt` (24 h), stores `requestHash` (SHA-256 of the canonical request) and `personId`. No stream.

Create flow: one `TransactWriteItems` with `Put` person (`attribute_not_exists(personId)`) and `Put` idempotency key (`attribute_not_exists(idempotencyKey)`). On `TransactionCanceledException` because the key exists: read the key item; same hash → replay; different hash → `422`.

### 5.5 Event — `PersonCreated`

Bus: `person-events-{stage}`. `source`: `tikkie.person-service`. `detail-type`: `PersonCreated`. `detail`:

```json
{
  "metadata": {
    "eventId": "<stream record eventID>",
    "eventVersion": "1",
    "occurredAt": "<createdAt>",
    "correlationId": "<correlationId>"
  },
  "data": { "personId": "3f1c…" }
}
```

Produced entirely by the Pipe: filter `eventName = INSERT`, input template projects only these fields. **No PII in the event.** Publish a JSON Schema for it in `docs/events/person-created.v1.json`. Consumers must be idempotent on `eventId` (delivery is at-least-once).

---

## 6. Infrastructure details (CDK, Java)

- `PersonServiceApp` creates two stages, `Dev` and `Prod`. Account and region come from context or environment variables (`CDK_DEFAULT_ACCOUNT`, `CDK_DEFAULT_REGION`, or `-c devAccount=… -c prodAccount=…`). **No account IDs committed.** Default region `eu-west-1`.
- `EnvironmentConfig` (record) holds per-stage values: log level, log retention, removal policy, deletion protection, PITR, alarm thresholds, API throttling rate/burst, Lambda reserved concurrency, optional DynamoDB warm throughput for prod.
- No hardcoded physical resource names (let CloudFormation generate them), except the bus name, which includes the stage.
- **Storage**: KMS customer-managed key (rotation on) for both tables, PITR on, `deletionProtection` and `RemovalPolicy.RETAIN` in prod, `DESTROY` in dev.
- **API**: `RestApi` with request validator + model from 5.3, `AuthorizationType.IAM`, stage throttling from config, access logging **without request bodies**, X-Ray tracing on.
- **Lambda**: Java runtime from section 3, 1024 MB, arm64 if compatible, SnapStart on published versions; API integrates with an **alias**, not `$LATEST`. Environment: table names, log level, Powertools service name. Grants via `grant*` methods only (`grantWriteData` on Persons, read/write on IdempotencyKeys). Log group with explicit retention and a **CloudWatch Logs data protection policy** masking phone numbers and addresses.
- **Events**: `EventBus`, archive on the bus (retention 30 days in prod), SQS DLQ (KMS-encrypted) for the Pipe, `CfnPipe` (L1; use a stable L2 only if one exists in `aws-cdk-lib`) with an IAM role built from grants (`grantStreamRead`, `grantPutEventsTo`, `grantSendMessages`), starting position `LATEST`, max retry attempts 10.
- **Monitoring**: alarms on Lambda errors, API `5XXError`, DLQ `ApproximateNumberOfMessagesVisible > 0`, Pipe failures. SNS topic as the alarm action (no subscriptions committed).
- **cdk-nag**: apply `AwsSolutionsChecks` to the app; every suppression has a written reason.
- Outputs: API URL, bus name/ARN.

---

## 7. Observability and PII hygiene

- Powertools logging (structured JSON) with `correlationId`, `personId`, cold-start flag; Powertools tracing (X-Ray) on the handler and DynamoDB calls; Powertools metrics: `PersonCreated`, `IdempotentReplay`, `ValidationFailed`.
- **Never log request bodies or domain objects with PII.** PII fields are wrapped in `Sensitive<T>`, whose `toString()` returns `***`; records that hold PII override `toString()`.
- A unit test asserts that `Person.toString()` contains no first name, last name, phone or street.

---

## 8. Testing strategy

| Level | What | Tooling |
|-------|------|---------|
| Unit | Value objects (valid/invalid cases, E.164 normalisation, NL postal codes, Unicode names), use case with a fake repository, handler mapping (status codes, problem details) | JUnit 5, AssertJ, Mockito |
| Architecture | `domain` package has no dependency on `software.amazon..`, Jackson or `adapter` | ArchUnit |
| Integration | `DynamoDbPersonRepository` against DynamoDB Local: create, duplicate key replay, key reuse with different payload, concurrent duplicates (two threads, one person) | Testcontainers |
| Infrastructure | Template assertions: stream `NEW_IMAGE`, PITR on, KMS key, IAM auth on `POST /person`, request validator present, Pipe filter on `INSERT`, DLQ configured, no `*` actions in IAM policies, prod has deletion protection; plus a snapshot test | CDK `assertions` |
| End-to-end | Against a deployed `dev` stack: the test stack adds a rule routing `PersonCreated` → SQS test queue; signed `POST`, then poll the queue and assert the event arrives with the returned `personId` and no PII. Run with `mvn verify -Pe2e` | AWS SDK v2 |
| Load | Ramp to a configurable RPS against `dev` (separate account recommended) | k6 |

---

## 9. CI/CD

`.github/workflows/ci.yml` on every push and PR: build, unit + integration + architecture + infrastructure tests, `cdk synth` (fails on cdk-nag errors), upload `cdk.out` as artifact.

Deployment (documented, and optionally a workflow): GitHub OIDC role per account (no long-lived keys), `cdk deploy "Dev/*"` on merge to `main`, `cdk deploy "Prod/*"` behind a manual approval environment. If no AWS account is available, document the commands in the README instead.

---

## 10. Phases (implement in order)

Each phase: keep the build green, add tests with the code, one commit (conventional commit message).

0. **Skeleton** — parent POM, `service`, `infra`, `e2e` modules, `cdk.json`, `.gitignore`, empty README, CI workflow running `mvn verify`.
   *Done when*: `mvn verify` passes in CI.
1. **Domain** — value objects, `Person`, `Sensitive<T>`, `ValidationException`, unit tests, ArchUnit test.
   *Done when*: all validation rules in 5.3 have passing positive and negative tests.
2. **Application + persistence** — use case, repository port, DynamoDB adapter with the transaction from 5.4, Testcontainers tests including concurrent duplicates.
3. **API adapter** — handler, DTOs, problem details, correlation ID, Powertools logging/metrics/tracing, handler tests.
4. **Infrastructure core** — stage/stack/config, storage, API, Lambda with SnapStart + alias, template tests.
5. **Event pipeline** — bus, archive, Pipe, DLQ, event JSON Schema, template tests.
6. **Hardening** — monitoring, log data protection policy, cdk-nag with justified suppressions.
7. **E2E + load** — e2e module and test-only rule/queue, k6 script.
8. **Docs** — README (architecture diagram in Mermaid, how to build/test/deploy, decisions summary, scaling notes, next steps) and ADRs for the decisions in section 2.

---

## 11. Rules for the implementer

- Don't change a design decision in section 2 silently. If it doesn't work, stop and explain why, then propose an alternative.
- No secrets, account IDs or real personal data in the repo; sample data is fictional.
- Least privilege only through CDK `grant*` methods; no `*` in IAM actions or resources.
- Keep the `domain` package free of frameworks.
- Prefer clarity over cleverness: this code will be read and discussed in an interview.

---

## 12. Review checklist (used when reviewing the delivered code)

- [ ] Only one write path; no `PutEvents` call anywhere in the Lambda.
- [ ] Event contains no PII; Pipe filter only passes `INSERT`.
- [ ] Idempotency: transaction covers both items; replay returns the original response; key reuse with a different payload returns `422`; concurrent duplicate test exists.
- [ ] Phone stored as E.164; NL postal code normalised; names accept Unicode, apostrophes and hyphens.
- [ ] API uses IAM auth, request validator and model; access logs exclude bodies.
- [ ] Lambda behind an alias with SnapStart; grants are least privilege.
- [ ] KMS key, PITR, deletion protection and retain policy in prod; log retention set.
- [ ] DLQ + alarm; bus archive configured.
- [ ] Dev and Prod differ only through `EnvironmentConfig`; no hardcoded account IDs or table names.
- [ ] cdk-nag passes; every suppression justified.
- [ ] Template tests cover the items above; ArchUnit test protects the domain.
- [ ] README explains decisions and trade-offs in a way that maps to the interview conversation.


---

## 13. Amendments

Decisions taken with the owner after this brief was written. Each ADR supersedes the parts of this brief it names. The original text above is unchanged.

| ADR | Supersedes | Change |
|-----|------------|--------|
| [0009](adr/0009-per-caller-idempotency-scope.md) | §5.4 IdempotencyKeys partition key | Keys are scoped per caller (IAM principal, STS session name stripped). Partition key value is `<callerId>\|<idempotencyKey>`. |
| [0010](adr/0010-request-fingerprint.md) | none (clarifies §5.4 `requestHash`) | SHA-256 over a canonical, versioned form, behind a port; a KMS HMAC is a roadmap item. |
| [0011](adr/0011-idempotent-write-semantics.md) | §5.4 create flow | Cancellations branch on reason codes, with backoff and up to 3 attempts. Expired keys count as absent and may be overwritten (`attribute_not_exists(idempotencyKey) OR expiresAt <= :now`). `ClientRequestToken` is the candidate personId. |
| [0012](adr/0012-unsupported-media-type.md) | §5.2 response list | Adds `415 Unsupported Media Type` for any non-JSON `Content-Type`; API Gateway skips body validation for unmapped content types. The handler alone enforces `Idempotency-Key` (§5.1 "required" still holds). |
| [0013](adr/0013-iam-wildcard-exceptions.md) | §11 "no `*` in IAM actions or resources" | Three unavoidable, test-enforced exceptions: X-Ray write actions on `*`, the log-group ARN stream suffix, KMS prefix actions on the stage key. |
| [0015](adr/0015-cdk-nag-acknowledged-findings.md) | §6 cdk-nag ("every suppression has a written reason") | Findings are acknowledged per finding id with an ADR reason (cdk-nag 3 has no suppressions API); no WAF and no API Gateway execution logging. |
