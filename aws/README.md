# AWS Workshop

[한국어](README.ko.md) | English

The AWS workshop contains local-first examples for S3 storage, DynamoDB persistence, event routing,
scheduled workflows, queue/topic messaging, vector search, access decisions, and observability. Use
`s3-spring-cloud/` when you want to see Spring Cloud AWS and `S3Template` in a small runnable sample. Use
`storage-abstraction/` when you want a service boundary that can switch between local files, S3, and
pre-signed S3 URLs through Spring profiles. Use `ktor-dynamodb/` when you want Ktor routes,
DynamoDB table bootstrap, conditional writes, and optimistic updates against a local AWS emulator.
Use `eventbridge-scheduler/` when you want to map an order workflow into an EventBridge event and a
delayed Scheduler request without real AWS credentials. Use `sqs-sns-coroutines/` when you want to
publish order notifications to SNS, consume SQS messages, and classify ack, retry, or dead-letter
outcomes from coroutine code. Use `s3-vectors-access-grants/` when you
want to keep S3 Vectors upsert/query separate from S3 Access Grants read decisions. Use
`cloudwatch-imds-observability/` when you want to learn CloudWatch metrics, CloudWatch Logs,
Micrometer publishing, and explicit IMDS boundaries without real AWS credentials.

## Architecture

![AWS Workshop architecture diagram](../docs/images/readme-diagrams/aws-readme-architecture-01.png)

All modules keep the default learning path local-first. The S3 and DynamoDB modules use local
AWS-compatible infrastructure, while the EventBridge Scheduler, S3 Vectors/Access Grants, and
SQS/SNS, CloudWatch/IMDS modules use local adapter beans so default tests do not call real AWS
services or IMDS.

## Module Guide

| module | Gradle task path | use it for |
| --- | --- | --- |
| `s3-spring-cloud/` | `:aws-s3-spring-cloud` | Spring Cloud AWS `S3Template`, AWS SDK v2 `S3Client`, bucket creation, object upload, object listing, and `ResourceLoader` access. |
| `storage-abstraction/` | `:aws-storage-abstraction` | `StorageService` with `local`, `s3`, and `s3-presigned` profiles, coroutine-friendly blocking I/O boundaries, and pre-signed URL behavior. |
| `ktor-dynamodb/` | `:aws-ktor-dynamodb` | Ktor REST routes, `DynamoDbKtorPlugin` table bootstrap, conditional writes, optimistic version updates, and local emulator readiness checks. |
| `eventbridge-scheduler/` | `:aws-eventbridge-scheduler` | Order workflow event envelopes, EventBridge publish status, delayed Scheduler request mapping, idempotency keys, and correlation ids. |
| `sqs-sns-coroutines/` | `:aws-sqs-sns-coroutines` | SNS publish requests, SQS polling, coroutine cancellation propagation, retry visibility changes, dead-letter reports, and Micrometer outcome metrics. |
| `cloudwatch-imds-observability/` | `:aws-cloudwatch-imds-observability` | CloudWatch metric/log publish intent, Micrometer meter publishing, failure isolation, and explicit IMDS metadata opt-in without real credentials. |
| `s3-vectors-access-grants/` | `:aws-s3-vectors-access-grants` | S3 Vectors document upsert/query boundaries, deterministic local vector ranking, and redacted S3 Access Grants read-decision reports. |

## Runtime Model

| concern | implementation |
| --- | --- |
| AWS endpoint | Local AWS-compatible emulator from `bluetape4k-testcontainers`, started by the sample or tests for emulator-backed modules. |
| Credentials | Static local credentials from the emulator; real AWS credentials are not required for local verification. |
| S3 client | AWS SDK v2 `S3Client`; blocking calls are wrapped in `Dispatchers.IO` in the storage abstraction module. |
| DynamoDB client | AWS Kotlin SDK `DynamoDbClient` installed through `DynamoDbKtorPlugin` in `ktor-dynamodb/`. |
| EventBridge and Scheduler | AWS SDK v2 `PutEventsRequestEntry` model plus local publisher/scheduler boundaries in `eventbridge-scheduler/`. |
| SQS and SNS messaging | bluetape4k `SqsOperations` and `SnsOperations` with local adapters in `sqs-sns-coroutines/`. |
| Spring integration | Spring Cloud AWS `S3Template` and `ResourceLoader` in `s3-spring-cloud/`; Spring profiles in `storage-abstraction/`. |
| Vector and access boundaries | `s3-vectors-access-grants/` uses bluetape4k `S3VectorsOperations` and `S3AccessGrantsOperations` local adapters by default. |
| Observability | `cloudwatch-imds-observability/` publishes local CloudWatch intent by default and reads IMDS metadata only when explicitly requested. |

## Run

```bash
./gradlew :aws-s3-spring-cloud:test
./gradlew :aws-storage-abstraction:test
./gradlew :aws-ktor-dynamodb:test --max-workers=1
./gradlew :aws-eventbridge-scheduler:test
./gradlew :aws-sqs-sns-coroutines:test
./gradlew :aws-cloudwatch-imds-observability:test
./gradlew :aws-s3-vectors-access-grants:test
```

Run the profile-driven storage sample directly when you want to compare backends:

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=local'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-presigned'
./gradlew :aws-ktor-dynamodb:run \
  -Dbluetape4k.aws.mode=local \
  -Dbluetape4k.aws.dynamodb.endpoint-url=http://localhost:4566 \
  -Dbluetape4k.aws.access-key-id=test \
  -Dbluetape4k.aws.secret-access-key=test
./gradlew :aws-cloudwatch-imds-observability:bootRun
./gradlew :aws-eventbridge-scheduler:bootRun
./gradlew :aws-sqs-sns-coroutines:bootRun
./gradlew :aws-s3-vectors-access-grants:bootRun
```

## Prerequisites

| item | requirement |
| --- | --- |
| JDK | Java 21 or newer. |
| Docker | Required for emulator-backed tests and S3 samples. |
| AWS account | Not required for the local workshop path. Real EventBridge/Scheduler, CloudWatch/IMDS, and S3 Vectors/Access Grants behavior is manual opt-in only. |
