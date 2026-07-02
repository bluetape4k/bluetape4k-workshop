# SQS + SNS Coroutine Messaging Workshop

[한국어](README.ko.md) | English

This example teaches a local-first coroutine boundary for AWS SNS publish and
SQS consume flows. The default beans are in-memory adapters, so learners can run
the application without an AWS account, AWS credentials, Docker, or LocalStack.
The test suite also includes a Floci/Testcontainers integration test that uses
real bluetape4k `SnsCoroutinesTemplate` and `SqsCoroutinesTemplate` clients
against a local AWS-compatible endpoint.

## Architecture

![SQS and SNS coroutine messaging architecture](../../docs/images/readme-diagrams/aws-sqs-sns-coroutines-readme-architecture-01.png)

`OrderNotificationMessagingService` is the single service boundary. It validates
the request, serializes an `OrderNotificationEvent`, publishes the JSON payload
through bluetape4k `SnsOperations`, polls bluetape4k `SqsOperations`, and returns
explicit reports instead of hiding side effects. Local `SnsOperations` and
`SqsOperations` beans are registered only when no real beans exist. The service
uses `Jackson.defaultJsonMapper` from `bluetape4k-jackson3` for JSON payloads.

## Request Flow

![SQS and SNS coroutine messaging sequence](../../docs/images/readme-diagrams/aws-sqs-sns-coroutines-readme-sequence-01.png)

The important part is the `alt consume outcome` branch. A message is either
handled and deleted, marked for retry by resetting visibility, or deleted as a
dead-letter outcome after the configured receive count. `CancellationException`
is always rethrown so coroutine cancellation is not converted into a report.

## What You Learn

| Topic | Workshop behavior |
| --- | --- |
| SNS publish boundary | Builds `SnsPublishRequest` with JSON body, subject, correlation id, idempotency key, and event type attributes. |
| SQS consume boundary | Polls once with learner-visible queue settings and returns one report per delivered message. |
| Retry classification | Handler failures call `changeVisibility(..., timeoutSeconds = 0)` and return `RETRY_REQUESTED`. |
| Dead-letter classification | Messages at or above `maxReceiveCount` are deleted and returned as `DEAD_LETTER`. |
| Metrics | `OrderNotificationMetrics` records publish timing and consume result counters with Micrometer. |
| Local safety | `bootRun` never contacts real AWS services by default; integration tests use Floci instead of real AWS. |

## Run Locally

```bash
./gradlew :aws-sqs-sns-coroutines:test
./gradlew :aws-sqs-sns-coroutines:bootRun
```

`test` starts a Floci Testcontainers AWS emulator. Use Docker locally, and keep
this module in sequential container-backed CI lanes.

The module is intentionally service-first. Use the test class as the executable
walkthrough for publish, ack, retry, dead-letter, validation, and cancellation
cases:

```bash
./gradlew :aws-sqs-sns-coroutines:test \
  --tests '*OrderNotificationMessagingServiceTest'
```

## Configuration

Default `src/main/resources/application.yml` keeps the sample self-contained:

```yaml
bluetape4k:
  aws:
    sqs-sns:
      topic-arn: arn:aws:sns:ap-northeast-2:123456789012:order-notifications
      queue-url: https://sqs.ap-northeast-2.amazonaws.com/123456789012/order-notifications
      subject: Order notification
      max-messages: 10
      wait-time-seconds: 1
      visibility-timeout-seconds: 30
      max-receive-count: 3
```

Replace the local `SnsOperations` and `SqsOperations` beans with real bluetape4k
AWS beans only in a manual environment where IAM permissions, cleanup, region,
cost, retry policy, and queue/topic subscription wiring are understood.

## Test Coverage

```bash
./gradlew :aws-sqs-sns-coroutines:compileKotlin
./gradlew :aws-sqs-sns-coroutines:compileTestKotlin
./gradlew :aws-sqs-sns-coroutines:test
```

The unit tests verify SNS request mapping, SQS ack, retry and dead-letter
classification, metrics, property validation, request validation, and coroutine
cancellation propagation. `OrderNotificationFlociIntegrationTest` verifies SNS
publish and SQS consume through real bluetape4k operation templates, Floci,
Awaitility `untilSuspending`, and `Jackson.defaultJsonMapper` without real AWS
credentials.
