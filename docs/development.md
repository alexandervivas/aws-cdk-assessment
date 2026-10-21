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
| End-to-end | Against a deployed Dev stage: create, replay, key reuse, validation, missing key, header casing, content types, and the `PersonCreated` event (schema, no personal data). Skipped unless pointed at a stack; see [Run the end-to-end tests](#run-the-end-to-end-tests). Test-only `E2eSupportStack` template tests in `E2eSupportStackTest` | `e2e/src/test/...` |
| Load | k6, ramping arrival rate, p95 under 1 s and under 1 % failed requests; see [Run the load test](#run-the-load-test) | `load/k6/create-person.js` |

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

## End-to-end support stack

The end-to-end tests read `PersonCreated` events from a queue. `E2eSupportStack` provides it: a rule on the existing `person-events-dev` bus, matching `source: tikkie.person-service` and `detail-type: PersonCreated`, that targets an SQS queue. The queue uses SQS-managed encryption, so EventBridge can send without a key policy and no extra KMS key is billed. It keeps messages for one day, rejects requests without TLS, and its policy lets only `events.amazonaws.com` send, and only for this rule (`aws:SourceArn`). It has no dead-letter queue, because it is a test sink ([0015](adr/0015-cdk-nag-acknowledged-findings.md)).

The stack exists only with `-c e2e=true`. Without it, `cdk synth` produces semantically identical Dev and Prod assemblies ([0016](adr/0016-e2e-support-stack.md)). It is a top-level stack, not part of a stage, and uses the Dev account and region. Deploy it into the same dedicated account as Dev ([ADR 0014](adr/0014-account-level-apigateway-logging-role.md)):

The rule needs the bus to exist, and CDK can't order stacks across assemblies, so deploy in two steps:

```bash
./mvnw -B package -DskipTests
npx cdk deploy "Dev/*" -c devAccount=<dev-account-id> -c prodAccount=<prod-account-id>
npx cdk deploy E2eSupportStack -c e2e=true -c devAccount=<dev-account-id> -c prodAccount=<prod-account-id>
```

Destroy `E2eSupportStack` before `Dev/*`. The stack outputs `QueueUrl`, which the end-to-end tests read as `E2E_QUEUE_URL`. Remove it with `npx cdk destroy E2eSupportStack -c e2e=true` with the same account context.

## Run the end-to-end tests

The tests in `e2e/` call a deployed Dev stage and read its events from the `E2eSupportStack` queue. They run only with the Maven profile `e2e` and only when both `E2E_API_URL` and `E2E_QUEUE_URL` are set. Otherwise a JUnit assumption skips them, so `./mvnw verify` and CI never need AWS.

Deploy `Dev/*` and `E2eSupportStack` first (see above), then take the URLs from the stack outputs:

```bash
export AWS_REGION=eu-west-1
export E2E_API_URL=$(aws cloudformation describe-stacks --stack-name Dev-PersonService \
  --query "Stacks[0].Outputs[?starts_with(OutputKey, 'ApiRestApiEndpoint')].OutputValue" --output text)
export E2E_QUEUE_URL=$(aws cloudformation describe-stacks --stack-name E2eSupportStack \
  --query "Stacks[0].Outputs[?OutputKey=='QueueUrl'].OutputValue" --output text)
./mvnw verify -Pe2e -pl e2e -am
```

- Requests are signed with SigV4 using the default AWS credentials chain (environment, profile, SSO). The caller needs `execute-api:Invoke` on `POST /person`, and `sqs:ReceiveMessage` and `sqs:DeleteMessage` on the queue.
- Every test uses a new `Idempotency-Key` and fictional data, so each run creates a few persons in Dev.
- The event test waits up to 60 s for `PersonCreated` with the returned `personId`, checks it against `docs/events/person-created.v1.json`, checks that none of the request's personal values appear in it, and deletes it. Other messages stay in the queue until they expire after a day. After a load test, purge the queue first (`aws sqs purge-queue --queue-url "$E2E_QUEUE_URL"`), or the poll has to read through the backlog.
- `HTTP/1.1` is used on purpose, so the lower-case `idempotency-key` test sends the header exactly as written.
- A request without `Content-Type` only has to get a 4xx. ADR 0012 expects 415 from the handler, but API Gateway may treat the request differently before it gets there. The test logs the observed status; record it in [Status](roadmap.md#status) after the first run.

## Run the load test

Run it only in the dedicated Dev account ([ADR 0014](adr/0014-account-level-apigateway-logging-role.md)), never against Prod. Every request creates a person, writes two DynamoDB items and publishes an event, so a run costs money and fills the tables, the bus archive and the end-to-end queue.

`load/k6/create-person.js` ramps to `RATE` requests per second over 30 s and holds it for `DURATION`. It fails if the 95th percentile is 1 s or more, or if 1 % or more of the requests return anything other than 201. Dev throttles at 10 requests per second with a burst of 20 (`EnvironmentConfig.dev()`), so a `RATE` above 10 shows up as 429s and failed thresholds.

k6 signs the requests itself with the jslib AWS signature module (`https://jslib.k6.io/aws/0.12.3/signature.js`, pinned). It reads credentials from environment variables only, not from profiles:

```bash
eval "$(aws configure export-credentials --format env)"
export AWS_REGION=eu-west-1
k6 run -e API_URL="$E2E_API_URL" -e RATE=8 -e DURATION=2m load/k6/create-person.js
```

Without a local k6, use the image: `docker run --rm -i -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN -e AWS_REGION grafana/k6:1.3.0 run --include-system-env-vars -e API_URL="$E2E_API_URL" - < load/k6/create-person.js`.

## First deploy checklist

Several behaviours can only be confirmed against a real account. Run these after the first deploy, and record the results in [roadmap](roadmap.md).

1. **Account.** Use a dedicated AWS account, or get the account owner's agreement: the stack sets the account-level API Gateway logging role ([0014](adr/0014-account-level-apigateway-logging-role.md)).
2. **Deploy.** `./mvnw -B package -DskipTests`, then `npx cdk deploy "Dev/*" -c devAccount=<dev> -c prodAccount=<prod>`, then the `E2eSupportStack` command above.
3. **End-to-end.** Run `./mvnw verify -Pe2e -pl e2e -am` with `E2E_API_URL`, `E2E_QUEUE_URL` and `AWS_REGION` set. Note the status the missing-Content-Type case logged, and check that `text/plain` really reaches the handler's 415 ([0012](adr/0012-unsupported-media-type.md)).
4. **Alarm delivery.** Subscribe an email address to the alarm topic, then run `aws cloudwatch set-alarm-state --alarm-name <LambdaErrors alarm> --state-value ALARM --state-reason test` and confirm the notification arrives. Then try adding `aws:SourceAccount` to CloudWatch's key grant and repeat ([0013](adr/0013-iam-wildcard-exceptions.md)).
5. **Pipe metrics namespace.** `aws cloudwatch list-metrics --namespace AWS/EventBridge/Pipes --metric-name ExecutionFailed` returns the pipe. If it doesn't, fix the `PipeFailures` alarm's namespace.
6. **Load.** Run k6 at the default rate. p95 should stay under 1 s, with no 429s below the dev throttle.
7. **Logs.** Search the function log group for a test person's name, phone number and postal code. There should be no hits, and the data protection policy should report no findings.
