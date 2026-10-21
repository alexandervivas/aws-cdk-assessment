# 0015. cdk-nag as a synthesis gate, with acknowledged findings

## Status

Accepted, 2026-10-21. Proposed in phase 6b and confirmed by the owner: no WAF, no API Gateway execution logging.

## Context

The brief asks for cdk-nag `AwsSolutionsChecks` on the app, with a written reason for every suppression (§6, §12). cdk-nag 3 is a CDK policy validation plugin. It has no `NagSuppressions` class. Findings are acknowledged with CDK's own `Validations.of(construct).acknowledge(...)`, and the id `RuleId[FindingId]` does what `appliesTo` did in version 2.

Most findings follow from decisions already recorded. Two follow from choices no ADR covered yet: no AWS WAF on the API, and no API Gateway execution logging.

## Decision

- `PersonServiceApp.checkAwsSolutions` registers the pack with `Validations.of(app).addPlugins(...)`. Any finding that isn't acknowledged fails `cdk synth` and the build.
- The app fails synthesis itself (`PersonServiceApp.newApp`). CDK CLI 2.1146 asks the app to leave validation failures to the CLI, then counts only those in the stacks it selected. A bare `cdk synth` selects none here, because every stack sits in a stage, so it would pass with findings. The app overrides that context and uses in-memory mode, so the exception names each finding.
- Each acknowledgement sits on the construct that causes the finding and names one finding id, never a whole rule on a whole stack (`NagAcknowledgements`). Every reason cites an ADR. `AwsSolutionsChecksTest` pins the full list and fails on a new one.
- **No WAF (`AwsSolutions-APIG3`).** Every request must carry a valid SigV4 signature from an IAM principal (ADR 0006), and the stage is throttled (ADR 0005). A web ACL adds a monthly fee per stage and per rule, for traffic that IAM already rejects.
- **No execution logging (`AwsSolutions-APIG6`).** API Gateway execution logs at `ERROR` level include body validation messages, and those can quote request values, which may be personal data. The execution log group is created by API Gateway, not by the stack, so the data protection policy can't cover it. Access logs stay on, without bodies.

The acknowledged findings, the same in Dev and Prod:

| Finding | Where | Why | ADR |
|---------|-------|-----|-----|
| `IAM5[Resource::*]` | Function role | X-Ray write actions have no resource-level permissions | 0013 |
| `IAM5[Action::kms:GenerateDataKey*]`, `IAM5[Action::kms:ReEncrypt*]` | Function role, Pipe role | Emitted by the KMS grants, scoped to the stage key | 0013 |
| `IAM4[Policy::...AmazonAPIGatewayPushToCloudWatchLogs]` | API Gateway logging role | Account-level role for access logging | 0014 |
| `APIG2` | REST API | Bodies are validated. Parameters aren't, because the handler alone enforces `Idempotency-Key` | 0005 |
| `APIG3` | API stage | No WAF, see above | 0015 |
| `APIG6` | API stage | No execution logs, see above | 0015 |
| `COG4` | `POST /person` | IAM authorisation, not Cognito | 0006 |
| `SQS3` | Dead-letter queue | It is the Pipe's dead-letter queue. cdk-nag recognises only SQS and Lambda dead-letter settings | 0001 |

## Consequences

- A new resource that breaks an AwsSolutions rule fails the build until it is fixed or acknowledged with a reason.
- `IAM5[Resource::*]` on the function role would also cover a future `*` resource on that role. The stack-level IAM walk (ADR 0013) still fails on it, so the gate isn't weakened.
- The KMS prefix-action acknowledgements are per role, not per key; the stack IAM walk (`IamWildcards`, ADR 0013) is what pins those actions to the stage key.
- The log data protection policy that this phase adds is defence in depth with partial coverage, because the service logs none of these values. Phone numbers are covered in E.164 form, which is how the service stores them. AWS's managed `Address` identifier doesn't cover the Netherlands, so Dutch addresses are covered only through the postal-code form `1234 AB`, and street names aren't covered. Names aren't masked: the managed `Name` identifier is left out because of its false-positive risk.
- `DDB3` doesn't fire, although Dev has point-in-time recovery off. The rule checks `AWS::DynamoDB::Table` only, and the tables are `TableV2` (`AWS::DynamoDB::GlobalTable`). PITR per stage is pinned by `PersonStorageTest` instead.
- An attack that IAM doesn't stop, such as a flood of signed requests from a compromised caller, is limited only by stage throttling.
- Diagnosing a request that fails inside API Gateway relies on access logs and X-Ray, not execution logs.

## Rejected alternatives

- **Stack-wide acknowledgements per rule.** Shorter, but they would hide the same finding on resources added later.
- **Turning on parameter validation only to clear `APIG2`.** No parameters are declared, so it would validate nothing.
- **Execution logging at `ERROR` with a data protection policy.** The stack doesn't own that log group.
- **A WAF web ACL with AWS managed rules.** Cost and operations for no gain while every caller is IAM-authenticated.
