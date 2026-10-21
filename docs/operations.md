# Operations

## Events: operations notes

- **Encryption.** The bus and its archive use AWS-owned keys, because events carry no personal data. The dead-letter queue uses the stage CMK.
- **First deploy.** The pipe reads from `LATEST`. A person created after the API is live but before the pipe reaches RUNNING gets no event. A pipe replacement overlaps old and new pipes, which can produce duplicates but not gaps. Consumers deduplicate on `metadata.eventId`.
- **Dead-letter queue runbook.** A message in the DLQ holds batch metadata only: shard id, sequence numbers and the failure condition. It holds no `personId`. Errors are logged to the pipe's own log group without execution data, because execution data would include personal data.
  1. Within 24 h, re-read the records from the Persons stream (`GetShardIterator AT_SEQUENCE_NUMBER`, then `GetRecords`) and re-emit `PersonCreated` for each `personId`.
  2. After 24 h the stream has trimmed. Find the affected persons by scanning Persons over the `createdAt` window of the failure and re-emit.
  3. Consumers deduplicate on `metadata.eventId`, so re-emitting twice is safe. A manual re-emit carries a new `eventId`, so consumers should also be idempotent on `personId` for `PersonCreated`.
- **Correlation id.** The correlation id is caller-supplied (`X-Correlation-Id`) and is broadcast and archived with every event. Callers must not put personal data in it.
- **Record age.** A record that keeps failing is dead-lettered after 6 h. The stream then still holds it for 18 h, so the runbook's first step applies.
- **Throughput.** Batch size 1 with `ParallelizationFactor` 10 per stream shard. That keeps up with the prod API rate without reordering a single person's records.

## Alarms

`infra/src/main/java/com/tikkie/person/infra/constructs/PersonMonitoring.java` creates six alarms per stage. Each one evaluates a single five-minute period, treats missing data as not breaching, and notifies one SNS topic. The topic is encrypted with the stage key, accepts only TLS, has no subscriptions committed, and its ARN is a stack output. Only CloudWatch alarms of the same account may publish to it: the topic policy's `aws:SourceAccount` and `aws:SourceArn` conditions are the scoping gate. The stage key grants CloudWatch `kms:Decrypt` and `kms:GenerateDataKey*` without conditions, per AWS's documented statement, because a condition key missing from CloudWatch's KMS request context would silently drop every notification. The residual confused-deputy risk is bounded: no data becomes readable, because SNS binds the ciphertext to the topic. Thresholds come from `EnvironmentConfig.alarmThresholds` (dev / prod).

| Alarm | Metric | Fires when | Means |
|-------|--------|------------|-------|
| `UnexpectedErrors` | `PersonService` `UnexpectedError`, `Service=person-service-{stage}` | Sum ≥ 5 / 1 | The handler caught a failure and answered 500. |
| `LambdaErrors` | `AWS/Lambda` `Errors`, `FunctionName` | Sum ≥ 1 / 1 | The function failed where the handler cannot catch it: a timeout, an init failure or a runtime crash. |
| `ApiServerErrors` | `AWS/ApiGateway` `5XXError`, `ApiName=person-service-{stage}`, `Stage` | Sum ≥ 5 / 1 | Clients got a 5XX, from the handler or from API Gateway itself. |
| `LambdaThrottles` | `AWS/Lambda` `Throttles`, `FunctionName` | Sum ≥ 1 / 1 | Lambda refused invocations for lack of concurrency; clients saw 5XX. |
| `PipeFailures` | `AWS/EventBridge/Pipes` `ExecutionFailed`, `PipeName` | Sum ≥ 1 / 1 | Publishing `PersonCreated` failed. The Pipe retries up to 10 times. |
| `DeadLetters` | `AWS/SQS` `ApproximateNumberOfMessagesVisible`, `QueueName` | Maximum > 0 | A `PersonCreated` gave up after all retries and needs replaying. The message holds its stream position, not the record. |

- The Powertools service name carries the stage, so the `UnexpectedError` metrics of two stages in one account never mix.
- Overlap is on purpose (defence in depth): one handler 500 triggers both `UnexpectedErrors` and `ApiServerErrors`, and throttles trigger `LambdaThrottles` plus `ApiServerErrors`.
- `PipeFailures` is a warning, because it may count retried attempts as well as final failures. `DeadLetters` is the actionable alarm.
- `DeadLetters` stays in ALARM while any message waits in the queue, so new dead letters in that time don't notify again. Drain the queue with the dead-letter queue runbook above to re-arm it.

## Scaling notes

- Both tables are on-demand, so there is no capacity to plan. A new table's on-demand limits start modest and grow with traffic. Warm throughput for prod is a roadmap item.
- Throttling and concurrency are linked. `EnvironmentConfig` refuses a config where the API burst limit exceeds the Lambda reserved concurrency, because the excess would be throttled by Lambda and reach clients as a 5XX instead of a 429. Prod: rate 100, burst 100, reserved concurrency 100. Dev: rate 10, burst 20, no reservation.
- Latency budget: repository work stops starting new calls after 6 s, each DynamoDB call is capped at 2 s (1 s per attempt, 2 attempts), and the Lambda times out at 10 s. API Gateway's limit is 29 s (`ProductionWiring.java`, `PersonFunction.java`).
- Event path: the Pipe uses batch size 1, so one failing record is retried and dead-lettered alone and a retry never republishes delivered records. `ParallelizationFactor` is 10, so ordering is kept per person, not across a whole shard, which is all one `PersonCreated` per person needs. If the stream still falls behind, raise the batch size and check the consumers' idempotency (`PersonEvents.java`).
- The Persons table is a single-region `TableV2`. Its stream uses `NEW_IMAGE`. Before adding a replica to make it a global table, change the stream to `NEW_AND_OLD_IMAGES`, which global tables require, and check that the Pipe filter still sees one `INSERT` per person.
- The dead-letter queue keeps messages 14 days.
