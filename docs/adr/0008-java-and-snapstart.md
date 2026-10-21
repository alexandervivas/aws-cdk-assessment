# 0008. Java 25 with SnapStart

Status: Accepted, 2026-10-21.

## Context

The assessment asks for Java. A JVM Lambda has a slow cold start without mitigation.

## Decision

- Java 25 for build and runtime (`Runtime.JAVA_25`, `maven.compiler.release` 25, asdf `.tool-versions`). Same version in CI.
- arm64, 1024 MB, 10 s timeout, SnapStart on published versions. API Gateway calls the `live` alias, so it always reaches a snapshotted version (`PersonFunction.java`).
- The DynamoDB client is built at class initialisation, so the snapshot contains it. It uses `url-connection-client`. Credentials resolve on the first call after restore. Other SDK HTTP clients are excluded from the jar.
- Powertools is used programmatically (logging, metrics, tracing). No AspectJ, no idempotency module.

## Consequences

- Cold starts are shorter but not gone. Restore time still shows in the first request after a new execution environment. Measuring it is part of the planned load test.
- Under SnapStart the Powertools `cold_start` log field is always false, because Powertools reads an on-demand init (a comment in `CreatePersonHandler.java` notes this). Do not use that field to count cold starts. Use the Lambda `Restore Duration` in the REPORT line.
- Anything created at init and meant to be unique per environment (random seeds, caches of credentials) must be created after restore.
- Each deploy publishes a version, and publishing runs the init to take the snapshot.

## Rejected alternatives

- Node or TypeScript Lambda: cheaper cold start, but not the requested language.
- Provisioned concurrency: removes cold starts at a fixed hourly cost and does not match a usage-scaled service.
