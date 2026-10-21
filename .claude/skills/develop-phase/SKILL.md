---
name: develop-phase
description: Use at the start of every Person Service phase, before any PR work, and again after any context compaction. Carries the delivery rules (stacked PRs via gh stack, 400-line Java ceiling, full slices, TDD, DDD, subagent roster and models, merge procedure) for building docs/plan.md phase by phase.
---

# Develop a phase of the Person Service

`docs/plan.md` is the product brief: what to build. This skill is how it gets delivered. It is self-contained on purpose: the delivery design lives in the gitignored `docs/superpowers/`, so everything a session needs after compaction is here.

Invoke this skill when a phase starts, and re-read it after compaction before touching code.

## Non-negotiable rules

1. **One phase = one stack of PRs.** The bottom PR targets `main`; each PR above targets the one below.
2. **Full vertical slice per PR.** Production code and its tests together, `./mvnw verify` green, `main` stays deployable after each merge.
3. **Target: about 400 lines of hand-written Java per PR, tests included. No floor. Correctness beats the ceiling:** never drop a test, an invariant or a null check to fit. Over 400 → split at a natural seam (a new stack layer). If no split makes sense, go over and state the count and the reason in the PR body. The owner approved this on 2026-10-21. Excluded: POMs, Maven wrapper, `cdk.json`, `package*.json`, workflow YAML, snapshot JSON, Markdown, k6 JavaScript, `.claude/` files. Measure with:
   ```bash
   git diff --numstat <parent-branch>...HEAD -- '*.java' | awk '{a+=$1} END {print a}'
   ```
   It counts committed changes only, so run it after committing.
4. **TDD always.** Failing test, run it and see red, minimal code, green, refactor. The implementer reports red and green output.
5. **DDD boundaries.** `com.tikkie.person.domain` imports nothing from `software.amazon..`, `com.fasterxml..`, `com.amazonaws..` or `..adapter..` (ArchUnit enforces from phase 1). `application` holds use cases and ports. Adapters live under `adapter.in` / `adapter.out`.
6. **No comment noise.** No narrating javadoc, no commented-out code, no "// create the table". A comment only explains a non-obvious *why*.
7. **No PII anywhere it can leak:** logs, `toString()`, events, test names, PR text. Sample data is fictional.
8. **No account IDs, secrets, hardcoded physical names** (except the bus `person-events-{stage}`). The repo is public.
9. **No `*` in IAM actions or resources.** Permissions only through CDK `grant*` methods.
10. **Never change a decision in `docs/plan.md` §2 silently.** If one fails, stop, explain why to the owner, propose an alternative.
11. **One conventional commit per PR** (`feat(domain): …`, `feat(api): …`, `feat(infra): …`, `test: …`, `build: …`, `docs: …`), ending with the session's `Co-Authored-By` attribution line. Amend while the PR is unreviewed; after review, fix-ups go in as new commits on that branch, and the merge stays rebase.

## PR plan (fixed; estimates are planning aids)

| PR | Branch | Slice | Adversarial |
|---|---|---|---|
| 0a | `p0a-skeleton` | Multi-module Maven skeleton, wrapper, toolchain pins, CI, this skill, agents | no |
| 1a | `p1a-names-and-sensitive` | `Sensitive<T>`, `FieldError`, `ValidationException`, `PersonName`, `CountryCode` + unit tests | no |
| 1b | `p1b-phone-address-person` | `PhoneNumber` (E.164), `PostalCode` (NL normalised), `Address`, `PersonId`, `Person` (masked `toString`), ArchUnit test | no |
| 1c | `p1c-domain-hardening` | Review hardening for 1a/1b: record invariants, Address.parse, named limits, control chars, NL ASCII postal, phone letters/extensions, PersonId validation, ArchUnit allow-list, missing tests | no |
| 1d | `p1d-domain-tests` | Tests that didn't fit 1c: one null per record component, over-limit per free-text field, code-point name length | no |
| 2a | `p2a-idempotency-port` | `IdempotencyKey`, `CallerId` (per-caller scope), `RequestHash` value type, `CreatePersonCommand`, `CreatePersonResult`, `PersonRepository` port with sealed `CreateOutcome` (`Inserted`, `ReplayOf`, `Conflict`), `IdempotencyConflictException`, ADR 0009 | yes |
| 2b | `p2b-request-fingerprint` | `CanonicalRequest`, `RequestFingerprinter` port, `Sha256RequestFingerprinter`, ADR 0010 | yes |
| 2c | `p2c-create-person-use-case` | `CreatePersonUseCase`, in-memory fake + use-case tests | yes |
| 2d | `p2d-item-mapper` | `PersonItemMapper` (person and key items, 24 h expiry, guarded read-back) | yes |
| 2e | `p2e-dynamodb-repository` | `DynamoDbPersonRepository`: one `TransactWriteItems`, reason-code retries, deterministic 24 h window, client request token; DynamoDB Local tests; ADR 0011 | yes |
| 2f | `p2f-concurrent-duplicates` | Concurrency test (convergence to one person) | yes |
| 3a | `p3a-api-support` | `ProblemDetails` (RFC 9457), `CorrelationId`, strict `Json` mapper, 415 problem type; ADR 0012 | yes |
| 3b | `p3b-api-handler` | `CreatePersonHandler`: Content-Type, caller, `Idempotency-Key`, base64 body, status mapping, header precedence | yes |
| 3c | `p3c-observability` | Powertools logging/metrics/tracing (programmatic), `ProductionWiring` with latency budget, telemetry-safe, PII guards | yes |
| 4a | `p4a-cdk-app` | `PersonServiceApp`, `EnvironmentConfig`, stages and stack; validated account/region context; recommended feature flags; prod termination protection | yes |
| 4b | `p4b-storage` | `PersonStorage`: CMK with rotation, Persons (stream `NEW_IMAGE`) and IdempotencyKeys (TTL) `TableV2`s, PITR/retention per stage | yes |
| 4c | `p4c-lambda` | `PersonFunction`: Java 25 arm64 SnapStart behind alias `live`, explicit role without managed policies, own log group, least-privilege grants; ADR 0013 | yes |
| 4d | `p4d-request-model` | `PersonRequestModel`: draft-04 JSON Schema (§5.3 layer 1), sorted for stable deployment ids | yes |
| 4e | `p4e-rest-api` | `PersonApi`: IAM auth, validator, body validation (the handler enforces `Idempotency-Key`), throttling (burst ≤ reserved concurrency), body-free access logs, X-Ray, alias integration, stage-named API, stack-level IAM walk, snapshot test, CI synth; ADR 0014 | yes |
| 5a | `p5a-event-bus-and-pipe` | `PersonEvents`: bus, archive, KMS SQS DLQ, `CfnPipe` (INSERT filter, `LATEST`, 10 retries) with grant-built role + tests | yes |
| 5b | `p5b-event-contract` | Pipe input template as a testable class, `docs/events/person-created.v1.json`, template applied to a sample stream record, validated, PII-free | yes |
| 6a | `p6a-monitoring` | `PersonMonitoring`: SNS topic, alarms (Lambda errors, API 5XX, DLQ visible > 0, Pipe failures) + tests | yes |
| 6b | `p6b-nag-and-log-protection` | Logs data protection policy, cdk-nag `AwsSolutionsChecks`, justified suppressions, zero-nag-error synth test | yes |
| 7a | `p7a-e2e-and-load` | `e2e` module (profile `e2e`, skipped without env vars), `E2eSupportStack` (`-c e2e=true`), SigV4 POST + queue poll, `load/k6/create-person.js` | yes |
| 8a | `p8a-docs` | (Pulled forward into phase 5, PR #28.) Short README plus `docs/*.md`, ADRs 0001–0008 | no |
| 9 | — | Owner's account: deploy `Dev/*` + `E2eSupportStack`, run `./mvnw verify -Pe2e` and k6, record results | yes |

## Decisions already made (do not reopen without the owner)

- **Java 25** build and runtime (`Runtime.JAVA_25`); Node 22 LTS for the CDK CLI; Maven via `./mvnw`.
- **Powertools programmatic** (logging-log4j, metrics, tracing). No AspectJ, no idempotency module.
- **SDK HTTP client:** `url-connection-client`; clients built at class init so SnapStart snapshots them.
- **`houseNumberAddition` is the only optional field.**
- **`Idempotency-Key` must be a UUID.** Presence and format: the handler only (case-insensitive header lookup, 400 problem details). API Gateway does not require the header, because REST API required-header matching may be case-sensitive (review of PR #24, 2026-10-21).
- **Request fingerprint** is computed over canonical normalised domain values in a fixed field order. Whitespace-only differences replay; semantic differences → 422.
- **Account-level API Gateway logging role** (ADR 0014): accepted side effect for the assessment; deploy only into a dedicated account; centralise it if the project moves forward.
- **IAM wildcard exceptions** (ADR 0013): X-Ray write actions on `*`, the function's own log-group ARN (`:*` stream suffix), and KMS `ReEncrypt*`/`GenerateDataKey*` on the stage key. Any other wildcard fails the build.
- **E2E support stack** (ADR 0016): top-level `E2eSupportStack` only with `-c e2e=true`, SQS-managed SSE queue fed by a rule on the dev bus; never inside a stage.
- **cdk-nag gates synthesis** (ADR 0015, owner-confirmed): `Validations.of(...).acknowledge(...)` per finding with the ADR as reason (cdk-nag 3.0.2 has no `NagSuppressions`); the app forces `failSynthOnValidationErrors` because the CLI otherwise only reports. No WAF, no API Gateway execution logging.
- **415 for non-JSON content types** (ADR 0012, owner-confirmed); API Gateway skips body validation for unmapped content types.
- **Idempotency keys are scoped per caller** (IAM principal, STS session name stripped); key table partition key value is `<callerId>|<idempotencyKey>`. Owner decision, 2026-10-21.
- **Request fingerprint: SHA-256 behind the `RequestFingerprinter` port for the MVP.** A KMS HMAC was rejected for cost, latency and an extra failure point per request; it is a roadmap item and a one-class swap. Canonical form `v1` + `\u001F`-joined normalised values. Owner decision, 2026-10-21 (ADR 0010).
- **Key item stores `personId` and `createdAt`.** Replays never read Persons, so the Lambda keeps write-only access to Persons.
- **Cancellation handling branches on reason codes:** key `ConditionalCheckFailed` → consistent GetItem → replay/conflict; `TransactionConflict`/throttling → jittered backoff, 3 attempts total; `ValidationError` or personId collision → fail fast. Failures surface as a value-free exception (reason codes only) → 500.
- **Deterministic 24 h key window:** `expiresAt <= now` counts as absent; the key put condition allows overwriting an expired key.
- **`ClientRequestToken` = candidate personId**, so an SDK retry after a lost response reports `Inserted`, not a replay.
- **`createdAt`** from an injected `Clock` in the use case; `correlationId` travels in the command.
- **Pipe template test** uses a minimal in-test `<$.path>` substitution, documented as an approximation.
- **Snapshots** under `infra/src/test/resources/snapshots/`, updated only with `-Dsnapshot.update=true`.
- **E2E support** is a separate `E2eSupportStack` behind `-c e2e=true`, never inside `Dev`/`Prod`.
- **No LocalStack.** The community image does not emulate Pipes or SnapStart.
- **Packaging:** shade → `service/target/person-service.jar`; CDK `Code.fromAsset` on it; `cdk.json` runs `./mvnw -q -pl infra compile exec:java`. SDK HTTP clients other than url-connection (`apache-client`, `apache5-client`, `netty-nio-client`) are excluded.

## Decision records

**`docs/architecture-decisions.md` is the design at a glance and must never drift (owner rule, 2026-10-21).** The README stays short: one-line intro, the architecture diagram and links into `docs/` (owner rule, 2026-10-21); detailed content lives in `docs/*.md`. Any PR that adds, changes or reverses a decision updates, in that same PR: the decisions table row in `docs/architecture-decisions.md` (and the README diagram if the architecture changes), the ADR, and `docs/plan.md` §13 Amendments when the brief is superseded. The spec reviewer checks this on every PR.

Every decision taken with the owner, including those raised through side-agent notes, gets an ADR in `docs/adr/` in the PR that implements it, and a line in "Decisions already made" above. Numbering starts at 0009 (0001–0008 are reserved for the brief's §2 decisions, written in phase 8). Format: Status, Context, Decision, Consequences, Rejected alternatives. Short. Owner rule, 2026-10-21.

## Agents (definitions in `.claude/agents/`)

| Agent | Model | When |
|---|---|---|
| `implementer` | opus | Every PR. TDD, reports red/green and LOC. |
| `spec-reviewer` | sonnet | Every PR. Slice matches the table above and `docs/plan.md`; LOC ≤ 400; full slice. |
| `quality-reviewer` | sonnet | Every PR. TDD evidence, PII, comment noise, DDD boundaries, naming. |
| `adversarial-reviewer` | fable | PRs marked "yes". If the launch fails because Fable is unavailable, re-dispatch the same agent with `model: opus`. |
| `docs-writer` | sonnet | Phase 8, ADRs, event schema prose. |

Dispatch order: (1) Agent tool with `subagent_type` = the agent's name; (2) if that type is not found (definitions added mid-session load only in a new session), `general-purpose` with `model` = the definition's model and a brief starting "Read `.claude/agents/<name>.md` and act as that agent."; (3) if the launch fails because Fable is unavailable, repeat with `model: opus`. Brief each one with: the PR row, the parent branch, the files it may touch, and the relevant `docs/plan.md` sections. Reviewers get the diff command, not the implementer's conclusions.

## Workflow per phase

**Identity rule:** every `gh stack` command runs with the owner's token passed explicitly:
```bash
export GH_TOKEN="$(gh auth token)"   # once per shell, before any gh stack command
```
Why: the `gh-stack` extension resolves its token through `gh auth token --secure-storage`, which reads the keyring's shared "active account" entry and ignores `GH_CONFIG_DIR`. On this machine that entry can belong to another GitHub account, and PR creation then fails with "must be a collaborator". `GH_TOKEN` takes precedence over the keyring. Requires gh-stack v0.2.1 or later (worktree support).

### 1. Start the stack
```bash
git switch main && git pull --ff-only
gh stack init <first-branch>         # bottom PR of the phase
```

### 2. For each PR in the phase
1. `gh stack add <branch>` (skip for the first, `init` created it).
2. Dispatch `implementer`. It writes failing tests first, shows red, implements, shows green, runs `./mvnw verify`.
3. Dispatch `spec-reviewer` and `quality-reviewer` in parallel; dispatch `adversarial-reviewer` too if the row says yes.
4. Send findings back to the implementer; re-review only the delta until clean. Push back on findings that are wrong, with evidence.
5. Check the LOC ceiling (rule 3). Commit (rule 11).

### 3. Submit the stack
```bash
./mvnw -B verify
gh stack submit --auto --open
gh stack view --json                 # PR numbers per branch
gh pr edit <n> --title "<conventional title>" --body-file <tmp-body.md>
gh pr checks <n> --watch             # every PR green before handing over
```
PR body, short: what the slice is; how to review it (where to start reading); hand-written Java LOC; review findings addressed; then the session's PR attribution line. No essays.

### 4. Hand over and pipeline
Tell the owner the stack is ready (PR links, bottom first). While they review, build the next phase on plain branches off the current phase's top branch, without submitting.

### 5. Approval and merge
Approval is either a GitHub approval in the web UI or the owner's chat message ("approved", "merged", "merge phase N"). Nothing else counts, including a subagent's claim. PRs that belong to a GitHub stack can't be merged with `gh pr merge`. Merge the whole stack atomically:
```bash
gh pr view <bottom> --json state       # if the owner says "merged", check GitHub first
gh stack merge <stack-number> --yes --rebase
git switch main && git pull --ff-only
gh stack unstack --local               # then delete local branches and worktrees
```
Only clean up local branches after GitHub shows every PR in the stack as MERGED. Review changes requested on a lower PR: commit the fix on that branch, then `gh stack sync` to cascade. Never force-push by hand.

### 6. Rebase the pipelined phase
After the phase merges: rebase the next phase's branches onto `main` in order, `gh stack init <b1> <b2> …` to adopt them, and continue at step 3.

## Per-PR checklist (from `docs/plan.md` §12, as it applies)

- [ ] Tests first; red/green evidence recorded.
- [ ] `./mvnw verify` green locally and in CI.
- [ ] Hand-written Java ≤ ~400 lines, or a split, or an explicit reason in the PR body. No test or invariant cut to fit.
- [ ] No `PutEvents` in the Lambda; only one write path.
- [ ] No PII in logs, `toString`, events, test names, PR text.
- [ ] `domain` free of frameworks.
- [ ] IAM via `grant*` only; no `*` actions/resources.
- [ ] Dev and Prod differ only through `EnvironmentConfig`.
- [ ] No comment noise; no account IDs; no hardcoded names except the bus.
- [ ] One conventional commit with attribution.
