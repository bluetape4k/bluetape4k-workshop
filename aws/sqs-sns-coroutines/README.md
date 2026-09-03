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
handled and deleted, marked for retry by resetting visibility, or locally
discarded with a `DEAD_LETTER` classification after the configured receive
count. This workshop does not implement durable DLQ handoff; real AWS usage
should rely on SQS redrive policy or add an explicit DLQ publish boundary before
source deletion. `CancellationException` is always rethrown so coroutine
cancellation is not converted into a report.

## What You Learn

| Topic | Workshop behavior |
| --- | --- |
| SNS publish boundary | Builds `SnsPublishRequest` with JSON body, subject, correlation id, idempotency key, and event type attributes. |
| SNS PublishBatch boundary | Maps up to 10 order events to `SnsPublishBatchRequest` and keeps successful, per-entry failed, and unresolved transport entries visible. |
| SQS consume boundary | Polls once with learner-visible queue settings and returns one report per delivered message. |
| SQS Observation listener | Opt-in `@SqsListener` fixture keeps receive/process/ack observations parented across coroutine suspension, with manual ack and visibility heartbeat. |
| Retry classification | Handler failures call `changeVisibility(..., timeoutSeconds = 0)` and return `RETRY_REQUESTED`. |
| Dead-letter classification | Messages at or above `maxReceiveCount` are deleted and returned as local `DEAD_LETTER` discard reports; durable DLQ handoff is intentionally out of scope. |
| Metrics | `OrderNotificationMetrics` records publish timing and consume counters, classifying success, retry, failure, and cancellation without counting cancelled work as success. |
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
cost, retry policy, queue/topic subscription wiring, and SQS redrive/DLQ policy
are understood. The local adapters keep publish and consume as separate
boundaries; they do not simulate SNS-to-SQS fanout or delayed visibility.

## PublishBatch walkthrough

`OrderNotificationMessagingService.publishBatch` is the consumer example for the
new bluetape4k 2.0.0 SNS batch contract. It validates a non-empty list of at most
10 requests, uses each trimmed `idempotencyKey` as the AWS entry ID, and preserves
the `SnsPublishBatchResult` split between `successful` and `failed` entries. A
duplicate entry ID or blank payload fails before the SNS call. A transport or
protocol failure is returned as `FAILED` with bounded `completedEntryIds` and
`unresolvedEntryIds`; the example deliberately does not retry an ambiguous
publish automatically.

```kotlin
val report = service.publishBatch(
    listOf(orderPlaced, paymentCaptured),
)

report.successful.forEach { entry ->
    println("published ${entry.idempotencyKey}: ${entry.messageId}")
}
report.failed.forEach { entry ->
    println("failed ${entry.idempotencyKey}: ${entry.code}")
}
```

The Floci integration test exercises the real `SnsCoroutinesTemplate.publishBatch`
mapping without AWS credentials. The unit tests cover the 1–10 boundary, duplicate
IDs, blank payload, partial response mapping, cancellation propagation, and the
no-automatic-retry transport boundary.

## SQS Observation listener walkthrough

`SqsObservationExampleConfiguration` is an opt-in consumer fixture for the
bluetape4k 2.0.0 SQS listener observation lifecycle. The default remains disabled,
so the existing one-shot `consumeOnce` path and its retry/redelivery behavior are
unchanged. Enable the fixture only when you want to inspect listener lifecycle
telemetry:

```yaml
bluetape4k:
  aws:
    sqs:
      observation:
        enabled: true
```

The equivalent flat property is `bluetape4k.aws.sqs.observation.enabled=true`.

The example listener is deliberately `autoStartup = false`. Start it explicitly
after the application has been wired:

```kotlin
val listeners = context.getBean(SqsMessageListenerContainerRegistry::class.java)
listeners.start(OrderNotificationObservationListener.LISTENER_ID)
```

`OrderNotificationObservationListener` receives an `SqsReceivedMessage`, creates a
small child `Observation` while the SQS process observation is current, calls the
existing `OrderNotificationHandler`, and acknowledges only after the handler
returns. The listener container owns receive/process/ack observations, propagates
Micrometer context across coroutine suspension, and issues a one-second visibility
heartbeat while the handler is running. A `CancellationException` is rethrown, so
it cannot be reported as success or trigger an acknowledgement. The bounded
`OrderNotificationObservationRecorder` stores only stage, outcome, attempt,
delivery, and acknowledgement action; message bodies, receipt handles, and full
queue URLs are not retained.

The executable tests cover four boundaries:

```bash
./gradlew :aws-sqs-sns-coroutines:test \
  --tests '*SqsObservationExampleTest'
```

They prove the default-disabled path, active process parentage plus heartbeat and
ack, `ObservationRegistry.NOOP` compatibility, and cancellation without an ack.
No durable DLQ, exactly-once delivery, global tracing backend, or real AWS
credentials are required.

## Spring Modulith SNS/SQS externalization walkthrough

`AwsModulithMessagingExampleConfiguration` is an opt-in Spring Modulith
externalization fixture built on the existing SNS/SQS example. It maps a domain
`ModulithOrderPlacedEvent` to a versioned, allow-listed integration envelope,
hashes the correlation identifier into a bounded header, and chooses an SQS
FIFO message-group key when the configured destination ends in `.fifo`.

The fixture remains disabled in the default `bootRun` path. Enable all three
switches only in a local or Floci profile where the logical target and consumer
queue are configured:

```yaml
bluetape4k:
  aws:
    modulith:
      example:
        enabled: true
      events:
        enabled: true
        producer:
          enabled: true
        consumer:
          enabled: true
          source-mode: direct
          queue: order-notifications
          redrive-required: false
        targets:
          order-notifications:
            service: sqs
            destination: order-notifications
```

`ModulithExternalizationService.publish` reports success only after the
Spring Modulith transport future completes. `consumeOnce` invokes the public
bluetape4k SQS consumer and deletes the source message only after normal event
dispatch; handler, unknown-type/version, malformed-envelope, and partial
failure paths reset visibility and return `RETRY_REQUESTED`. Duplicate delivery
is kept safe by the library idempotency store, while private payload fields and
raw correlation values never enter the external envelope. Cancellation is
re-thrown so it cannot be mistaken for publication success or an acknowledgement.

The executable fixture tests cover disabled startup, redaction and dispatch,
ack-after-success, visibility retry on handler failure, and FIFO routing:

```bash
./gradlew :aws-sqs-sns-coroutines:test \
  --tests '*ModulithExternalizationExampleTest'
```

The default adapters are local and the integration path uses Floci; this
example does not claim durable exactly-once processing, cross-region FIFO, or
real AWS access by default.

## Test Coverage

```bash
./gradlew :aws-sqs-sns-coroutines:compileKotlin
./gradlew :aws-sqs-sns-coroutines:compileTestKotlin
./gradlew :aws-sqs-sns-coroutines:test
```

The unit tests verify SNS request mapping, SQS ack, retry and dead-letter
classification, metrics, failure/cancellation metric classification, property
validation, request validation, and coroutine cancellation propagation.
`OrderNotificationFlociIntegrationTest` verifies SNS
publish and SQS consume through real bluetape4k operation templates, Floci,
Awaitility `untilSuspending`, and `Jackson.defaultJsonMapper` without real AWS
credentials.
