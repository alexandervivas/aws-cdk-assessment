# 0016. A separate, opt-in stack for end-to-end tests

## Status

Accepted, 2026-10-21. Decided during delivery.

## Context

The end-to-end tests (brief §8) must check that `PersonCreated` actually reaches the bus. Something has to subscribe to the bus for the test to read events. If that subscriber were part of the `Dev` stage, Dev and Prod would differ by more than `EnvironmentConfig` (§12), and every Dev deploy would carry test infrastructure.

## Decision

`E2eSupportStack` (`infra/.../E2eSupportStack.java`) is a top-level stack, outside both stages, created only with `-c e2e=true`. It uses the Dev account and region. It has a rule on `person-events-dev` matching `source: tikkie.person-service` and `detail-type: PersonCreated`, targeting an SQS queue with:

- SQS-managed encryption, not the stage CMK. Events carry no personal data, EventBridge can send without a key policy, and no extra key is billed.
- One-day retention, TLS enforced, and a queue policy that allows only this rule (`aws:SourceArn`).
- No dead-letter queue, because it is a test sink ([0015](0015-cdk-nag-acknowledged-findings.md)).

## Consequences

- Without the flag, the Dev and Prod assemblies are semantically identical to before. Snapshots and cdk-nag are unchanged.
- CDK can't order stacks across assemblies, so on a first deploy `Dev/*` goes first ([development](../development.md#end-to-end-support-stack)).
- Any principal allowed `events:PutEvents` on the dev bus can put matching events into the queue. The tests match on the `personId` they created.

## Rejected alternatives

- **Rule and queue inside the Dev stage.** Test infrastructure would ship with every Dev deploy, and Dev would differ from Prod outside `EnvironmentConfig`.
- **The tests create a rule and queue at runtime through the SDK.** That leaves resources behind when a test run aborts, and it needs broad IAM permissions for whoever runs the tests.
