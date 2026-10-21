# 0013. IAM wildcard exceptions

## Status

Accepted, 2026-10-21. Raised during review and confirmed by the owner.

## Context

The brief requires least privilege through CDK `grant*` methods, with no `*` in IAM actions or resources (§11). It also requires X-Ray tracing on the Lambda (§6) and a customer-managed KMS key on both tables (§6). Those requirements, plus writing logs to a dedicated log group, produce three wildcards that no `grant*` call can avoid.

## Decision

The function role keeps exactly these three wildcard forms:

1. **X-Ray:** `xray:PutTraceSegments` and `xray:PutTelemetryRecords` on `Resource: "*"`. X-Ray doesn't support resource-level permissions for these actions, so `*` is the only valid resource.
2. **CloudWatch Logs:** `logs:CreateLogStream` and `logs:PutLogEvents` on the function's own log group ARN. CloudFormation returns that ARN with a `:*` suffix that covers the group's log streams. It names one group, not `*`.
3. **KMS:** `kms:ReEncrypt*` and `kms:GenerateDataKey*`, as emitted by the table grants, scoped to the single key ARN. No other resource is affected.

`PersonFunctionTest` walks every IAM policy in both stages. It allows `*` only for that exact X-Ray statement, pins the exact KMS action set, and fails on any other wildcard or any `index/*` resource.

## Consequences

- Tracing and CMK encryption work as the brief requires.
- Any new wildcard fails the build instead of slipping in.
- The function role has no AWS managed policies. Log writes are granted on the function's own log group (`logGroup.grantWrite`), not through `AWSLambdaBasicExecutionRole`, whose `logs:*` actions on `*` would be a third, unscoped wildcard.
- `grant*` methods grant more DynamoDB actions than the service calls. Persons gets `DeleteItem` and `UpdateItem`. The keys table gets stream actions although it has no stream. This is accepted under the brief's rule that permissions come only from `grant*`. Tightening it means hand-written statements, which the brief rules out.
- The EventBridge Pipe role carries the same KMS prefix forms on the same stage key. They come from `grantStreamRead` and from `Queue.grantSendMessages` on the CMK-encrypted dead-letter queue. The pipe writes only to its own log group, whose ARN carries the same `:*` stream suffix. The stack-level IAM walk applies the same exceptions to every role.
- A fourth, account-level exception arrives with the REST API: API Gateway's CloudWatch Logs role carries the AWS managed policy `AmazonAPIGatewayPushToCloudWatchLogs`. See [ADR 0014](0014-account-level-apigateway-logging-role.md). A stack-level test allows exactly that one managed policy ARN.
- Key policies are not IAM policies, so the stack IAM walk doesn't cover them. The stage key policy grants `cloudwatch.amazonaws.com` `kms:Decrypt` and `kms:GenerateDataKey*` for the encrypted alarm topic, with no condition. That is AWS's documented statement for CloudWatch alarms publishing to a KMS-encrypted SNS topic. Sources disagree on whether `kms:ViaService` and `aws:SourceAccount` are present in CloudWatch's KMS request context, and if either is missing every alarm fires but no notification is delivered. The topic policy is the scoping gate: its `aws:SourceAccount` and `aws:SourceArn` (`alarm:*`) conditions let only this account's alarms publish. The residual confused-deputy risk is bounded: the grant makes no data readable, because SNS binds the ciphertext to the topic.
- cdk-nag's `AwsSolutions-IAM5` findings for the X-Ray resource and the KMS prefix actions are acknowledged per finding on each role, citing this ADR ([0015](0015-cdk-nag-acknowledged-findings.md)).

## Rejected alternatives

- **Attaching the managed `AWSXRayDaemonWriteAccess` policy.** It hides the same `*` instead of removing it.
- **Explicit KMS actions instead of the table grants.** This risks a deploy-time access failure that can't be tested without an AWS account. It can be revisited after the first real deploy.
- **Turning tracing off.** That contradicts §6.
