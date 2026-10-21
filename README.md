# Person Service

A serverless AWS service: `POST /person` stores a person in DynamoDB and publishes a thin `PersonCreated` event to EventBridge. Java 25 Lambda with SnapStart, infrastructure in AWS CDK for Java.

## Architecture

```mermaid
flowchart TD
    client["Client (SigV4 signed)"] --> api["API Gateway REST<br/>IAM auth, JSON Schema validation"]
    api --> lambda["Lambda, Java 25<br/>SnapStart, alias live"]
    lambda -->|"one TransactWriteItems"| persons[("DynamoDB Persons<br/>KMS, stream NEW_IMAGE")]
    lambda -->|"same transaction"| keys[("DynamoDB IdempotencyKeys<br/>KMS, TTL 24 h")]
    persons -->|stream| pipe["EventBridge Pipe<br/>filter INSERT, thin template"]
    pipe -->|failed records| dlq["SQS dead-letter queue<br/>KMS"]
    pipe --> bus["Event bus person-events-stage<br/>with archive"]
    bus --> consumers["Consumers' rules<br/>other teams"]
```

The Lambda makes one write and never calls EventBridge. The event is derived from the stream ([ADR 0001](docs/adr/0001-outbox-via-streams-and-pipes.md)). Stack wiring is in `infra/src/main/java/com/tikkie/person/infra/PersonServiceStack.java`.

## Documentation

- [Architecture decisions](docs/architecture-decisions.md): the design at a glance, with an ADR for each decision ([index](docs/adr/README.md))
- [API contract](docs/api.md): request, responses and problem types
- [Build, test and deploy](docs/development.md)
- [Operations](docs/operations.md): event pipeline runbook, alarms, scaling
- [Trade-offs, risks and privacy](docs/risks-and-privacy.md)
- [Status and roadmap](docs/roadmap.md)
- [Event schema](docs/events/person-created.v1.json) and the original [brief](docs/plan.md)
