# 0003. Synchronous 201 Created

Status: Accepted, 2026-10-21.

## Context

A caller creating a person needs the id and needs to know at once if the input is invalid. The write is one DynamoDB transaction, which finishes in milliseconds.

## Decision

`POST /person` does the work in the request and returns `201 Created` with `Location: /person/{personId}` and a body of `personId` and `createdAt` (`CreatePersonHandler.java`). Validation errors come back in the same response.

## Consequences

- The caller sees success only after the data is committed.
- Throughput is bounded by what Lambda and DynamoDB absorb. The stage throttles (`EnvironmentConfig.apiRateLimit`, `apiBurstLimit`) and prod reserves Lambda concurrency so a burst is shed at the API, not inside Lambda.
- The request has a time budget: 6 s for repository work, 2 s per DynamoDB call, 10 s Lambda timeout, below the 29 s API Gateway limit (`ProductionWiring.java`, `PersonFunction.java`).
- Moving to asynchronous creation later would change the contract to `202` and needs a status resource.

## Rejected alternatives

- API Gateway to SQS to Lambda with `202`: the caller gets no id and no validation result. It is worth it only if spikes exceed what DynamoDB can absorb, which is not the case for one small transaction.
