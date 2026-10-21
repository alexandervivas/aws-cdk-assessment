# Status and roadmap

## Status

Update this section when a phase lands.

| Phase | Scope | State |
|-------|-------|-------|
| 0 | Skeleton, CI | Built |
| 1 | Domain | Built |
| 2 | Application and DynamoDB persistence | Built |
| 3 | API adapter, logging, metrics, tracing | Built |
| 4 | Storage, function, API, stage and stack | Built |
| 5a | Event bus, archive, Pipe, dead-letter queue | Built |
| 5b | Event JSON Schema at `docs/events/person-created.v1.json`, contract test | Built |
| 6 | Monitoring alarms and SNS topic, cdk-nag with justified suppressions, CloudWatch Logs data protection policy | Planned |
| 7 | End-to-end module with a test-only rule and queue, k6 load script | Planned |

Until phase 6, `EnvironmentConfig.alarmThresholds` is defined but unused, there are no alarms, and the log group has no data protection policy. Privacy relies on the code-level measures above.

## Next steps

- `GET /person/{id}`, list, update and delete. The event consumers cannot fetch person data until a read endpoint exists.
- A GDPR erasure flow. The thin event and write-only access to Persons keep the data in one place.
- HMAC fingerprint through KMS `GenerateMac` ([0010](adr/0010-request-fingerprint.md)).
- DynamoDB warm throughput for prod.
- A centrally managed API Gateway logging role, then `cloudWatchRole(false)` ([0014](adr/0014-account-level-apigateway-logging-role.md)).
- Per-caller rate limits, for example usage plans or a per-principal limit ([0006](adr/0006-iam-authentication.md)).
- Hand-written, tighter DynamoDB statements instead of `grant*` ([0013](adr/0013-iam-wildcard-exceptions.md)).
- Revisit explicit KMS actions after the first real deploy ([0013](adr/0013-iam-wildcard-exceptions.md)).
- Switch 415 to 400 if the owner prefers ([0012](adr/0012-unsupported-media-type.md)).
