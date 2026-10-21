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
| 6 | Monitoring alarms and SNS topic, cdk-nag with justified suppressions, CloudWatch Logs data protection policy | Built |
| 7 | End-to-end module with a test-only rule and queue, k6 load script | 7a built (`E2eSupportStack`) |

The alarms and the cdk-nag gate are described in [operations](operations.md#alarms) and [cdk-nag acknowledgements](operations.md#cdk-nag-acknowledgements), the log data protection policy in [risks and privacy](risks-and-privacy.md#privacy-and-pii).

## Next steps

- `GET /person/{id}`, list, update and delete. The event consumers cannot fetch person data until a read endpoint exists.
- A GDPR erasure flow. The thin event and write-only access to Persons keep the data in one place.
- HMAC fingerprint through KMS `GenerateMac` ([0010](adr/0010-request-fingerprint.md)).
- DynamoDB warm throughput for prod.
- A centrally managed API Gateway logging role, then `cloudWatchRole(false)` ([0014](adr/0014-account-level-apigateway-logging-role.md)).
- Per-caller rate limits, for example usage plans or a per-principal limit ([0006](adr/0006-iam-authentication.md)).
- Hand-written, tighter DynamoDB statements instead of `grant*` ([0013](adr/0013-iam-wildcard-exceptions.md)).
- Revisit explicit KMS actions after the first real deploy ([0013](adr/0013-iam-wildcard-exceptions.md)).
- After the first deploy, verify alarm delivery with `aws cloudwatch set-alarm-state`, then try re-adding `aws:SourceAccount` to CloudWatch's key grant ([0013](adr/0013-iam-wildcard-exceptions.md)).
- An alarm on `LogEventsWithFindings`, so a data protection match pages someone.
- A WAF web ACL if callers ever include clients outside IAM ([0015](adr/0015-cdk-nag-acknowledged-findings.md)).
