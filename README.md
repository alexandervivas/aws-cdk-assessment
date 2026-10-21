# Person Service

A serverless AWS service that registers people through a REST API and publishes a `PersonCreated` event, built with Java and AWS CDK.

## Build

```bash
npm ci
./mvnw verify
npx cdk synth
```

`./mvnw verify` builds the Lambda jar, `service/target/person-service.jar`, which the infra tests and `cdk synth` package. Without it, `cdk synth` fails and says so. To build the jar without running the tests, use `./mvnw -B package -DskipTests`.

The infra snapshot tests compare the Dev and Prod templates with `infra/src/test/resources/snapshots/`. After an intended infrastructure change, refresh them with `./mvnw -pl infra test -Dsnapshot.update=true`, which writes the files and fails, then rerun and review the diff. The API deployment's logical id hashes construct-path metadata, so it differs between a plain `cdk synth` and the snapshot. That is harmless.
