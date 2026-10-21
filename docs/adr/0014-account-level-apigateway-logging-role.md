# 0014. Account-level API Gateway logging role

## Status

Accepted for the assessment, 2026-10-21, decided with the owner. Revisit it before any shared or production deployment.

## Context

REST API access logging (§6) needs API Gateway to have a CloudWatch Logs role. That role isn't set per API. It is one setting per AWS account and region (`AWS::ApiGateway::Account`), shared by every REST API there. On a fresh account the setting is empty, and a stage with access logging fails to deploy until someone sets it.

## Decision

`PersonApi` uses `cloudWatchRole(true)`. The stack creates a role with the AWS managed policy `AmazonAPIGatewayPushToCloudWatchLogs` and points the account setting at it. The role and the account setting use `RemovalPolicy.RETAIN` in both stages, so destroying Dev cannot break Prod's logging when the two share an account.

This is an accepted side effect for the assessment. Deploy only into an AWS account used for nothing else, or one whose owner has agreed.

## Consequences

- Deploying into an account where other teams run REST APIs **replaces their logging role** with this one.
- `cdk destroy` does not undo it, because the role is retained.
- With no account context, Dev and Prod deploy to the same account, and both stacks own the `AWS::ApiGateway::Account` setting. The last deploy wins, and drift detection flags the other stack. Both roles carry the same policy, so logging keeps working.
- Each Dev stack delete and recreate orphans one retained role.
- `RETAIN` is hardcoded rather than taken from `EnvironmentConfig`, because the setting is account-wide, not per stage.
- The role carries an AWS managed policy. That is a fourth exception to the "grants only, no wildcards" rule (ADR 0013). cdk-nag `AwsSolutions-IAM4` will flag it, and the suppression cites this ADR.

## If the project moves forward

- Manage the account-level API Gateway logging role centrally, through a platform or landing-zone stack, or by the account owner.
- Set `cloudWatchRole(false)` in `PersonApi`, and document the role as a deployment prerequisite.
- Remove the related ADR 0013 exception and cdk-nag suppression.

## Rejected alternatives

- **Account prerequisite now.** Every fresh-account deploy would fail until someone sets the role by hand, which blocks the final deploy milestone.
- **Disabling access logging.** That contradicts §6.
- **Removal policy from config.** A Dev destroy would delete the role that Prod's logging depends on whenever both stages share an account.
