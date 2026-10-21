# Build, test and deploy

## Build and test

Toolchain: `.tool-versions` pins Corretto 25, Node 22 and the AWS CLI for asdf. `.envrc` uses `direnv` with asdf and sets `CDK_DEFAULT_REGION=eu-west-1`. Docker must be running, because the integration tests use Testcontainers with a DynamoDB Local image.

```bash
npm ci
./mvnw verify
npx cdk synth
```

`./mvnw verify` builds the Lambda jar, `service/target/person-service.jar`, which the infra tests and `cdk synth` package. Without it, `cdk synth` fails and says so. To build the jar without running the tests, use `./mvnw -B package -DskipTests`.

The infra snapshot tests compare the Dev and Prod templates with `infra/src/test/resources/snapshots/`. After an intended infrastructure change, refresh them with `./mvnw -pl infra test -Dsnapshot.update=true`, which writes the files and fails, then rerun and review the diff. The API deployment's logical id hashes construct-path metadata, so it differs between a plain `cdk synth` and the snapshot. That is harmless.

What each level covers (levels follow `docs/plan.md` §8):

| Level | Covers | Where |
|-------|--------|-------|
| Unit | Value objects, canonical request and fingerprint, use case with an in-memory repository, handler mapping, problem details, JSON mapper, telemetry | `service/src/test/.../domain`, `.../application`, `.../adapter/in/api` |
| Architecture | `domain` has no AWS, Jackson or adapter dependency | `service/src/test/.../ArchitectureTest.java` |
| Integration | Repository against DynamoDB Local: create, replay, key reuse, concurrent duplicates, mapper | `service/src/test/.../adapter/out/dynamodb` |
| Infrastructure | Template assertions per construct (storage, function, permissions, API, events, monitoring, log data protection), stage and app wiring, cdk-nag with zero unacknowledged findings, snapshots of Dev and Prod | `infra/src/test/...` |
| End-to-end | Planned, see Status | `e2e/` (module exists, tests skipped) |
| Load | Planned, see Status | none yet |

CI (`.github/workflows/ci.yml`) runs `npm ci`, `./mvnw -B verify`, `npx cdk synth`, and uploads the templates from `cdk.out` as an artifact.

## Deploy

Deploy into a dedicated AWS account ([ADR 0014](adr/0014-account-level-apigateway-logging-role.md)). The stack replaces the account's API Gateway CloudWatch Logs role and `cdk destroy` does not undo it.

```bash
./mvnw -B package -DskipTests
npx cdk bootstrap                      # once per account and region
npx cdk deploy "Dev/*" -c devAccount=<dev-account-id> -c prodAccount=<prod-account-id>
npx cdk deploy "Prod/*" -c devAccount=<dev-account-id> -c prodAccount=<prod-account-id>
```

- Pass both `devAccount` and `prodAccount` or neither, and they must differ. With neither, both stages use `CDK_DEFAULT_ACCOUNT` (`PersonServiceApp.java`). The region comes from `-c region`, `CDK_DEFAULT_REGION`, or `eu-west-1`.
- Prod has termination protection, table deletion protection, point-in-time recovery, retained resources and 100 reserved concurrent executions. Dev destroys its resources.
- The log level is set per stage through `POWERTOOLS_LOG_LEVEL` (dev `DEBUG`, prod `INFO`, from `EnvironmentConfig`). To change it for one function, edit the variable on the function or the config and redeploy.
- Outputs: API URL, bus name and bus ARN.
- Intended pipeline (not built): a GitHub OIDC role per account with no long-lived keys, `cdk deploy "Dev/*"` on merge to `main`, and `cdk deploy "Prod/*"` behind a manual approval environment (`docs/plan.md` §9).

Callers sign requests with SigV4 for service `execute-api` and need `execute-api:Invoke` on `POST /person`.
