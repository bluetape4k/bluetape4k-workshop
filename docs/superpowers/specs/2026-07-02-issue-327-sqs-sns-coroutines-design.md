# Issue 327 SQS/SNS Coroutine Messaging Design

## Context

Issue #327 adds an AWS queue/topic messaging example to `bluetape4k-workshop`.
The repository already has Kafka, Kafka outbox fallback, and transactional
outbox examples. The missing workshop contract is an AWS-native fanout plus
queue-consumer path that learners can run without AWS credentials.

Current source evidence:

- `bluetape4k-workshop` is a consumer repository and must use the root
  `bluetape4k-dependencies` BOM only.
- `bluetape4k-aws` exposes Spring Boot coroutine APIs for this scope:
  `io.bluetape4k.aws.spring.sns.SnsOperations`,
  `io.bluetape4k.aws.spring.sns.SnsPublishRequest`,
  `io.bluetape4k.aws.spring.sqs.SqsOperations`, and
  `io.bluetape4k.aws.spring.sqs.SqsReceivedMessage`.
- `bluetape4k-aws` also provides `MicrometerSqsOperations`, but the workshop
  still needs learner-visible business metrics for publish/consume outcomes.
- Existing `messaging/kafka-outbox-fallback` tests verify retry/dead-letter and
  Micrometer counters; this module should reuse that testing shape without
  copying the durable outbox design.

## Goal

Create `aws/sqs-sns-coroutines` as a local-first Spring Boot example that maps
an order notification into:

1. An SNS publish request.
2. An SQS consumer boundary for queue-delivered notifications.
3. Retry/dead-letter classification for handler failures.
4. Micrometer counters and latency timers for publish and consume outcomes.

Default tests must run with fake operations and no AWS credentials.

## Non-Goals

- Do not create a Kafka or transactional outbox implementation in this module.
- Do not require LocalStack or real AWS for default verification.
- Do not pin a bluetape4k module version outside the root BOM.
- Do not expose sensitive payloads or raw secrets in messages, logs, metrics, or
  README examples.

## Architecture

The module uses a small Spring Boot service layer:

- `OrderNotificationMessagingService` owns the publish and consume use cases.
- `SnsOperations` is the topic publish boundary.
- `SqsOperations` is the queue receive/delete/change-visibility boundary.
- `OrderNotificationHandler` is the learner-owned application handler.
- `OrderNotificationMetrics` records stable low-cardinality counters/timers.
- `SqsSnsMessagingProperties` holds the topic ARN, queue URL, polling limits,
  visibility timeout, max receive count, and message subject.

The local runtime registers in-memory `SnsOperations` and `SqsOperations` beans
only when the application has not provided real ones. This keeps `bootRun`
usable and leaves real AWS validation opt-in.

## Data Contract

`OrderNotificationRequest` fields:

- `orderId`: required nonblank.
- `customerId`: required nonblank.
- `eventType`: enum such as `ORDER_PLACED`, `PAYMENT_CAPTURED`, or
  `SHIPMENT_READY`.
- `message`: required nonblank learner-safe message text.
- `idempotencyKey`: required nonblank.
- `correlationId`: required nonblank.

`OrderNotificationEvent` is the JSON payload sent to SNS and consumed from SQS.
It carries the same identity and correlation fields plus `publishedAt`.

## Failure Contract

- Validation failures throw `IllegalArgumentException` through bluetape4k
  validation helpers.
- `CancellationException` is always rethrown.
- SNS publish failures return a failed publish report and record failure metrics.
- SQS handler success deletes the message and records `acked`.
- Handler failure before the max receive count requests immediate retry through
  `changeVisibility(..., timeoutSeconds = 0)` and records `retry`.
- Messages whose receive count is already at the max receive count are deleted
  and classified as `dead-letter` for the local workshop contract.

## Documentation Contract

`README.md` and `README.ko.md` must explain:

- The local-first default and fake-client boundary.
- SNS publish versus SQS consume responsibilities.
- How retry/dead-letter classification differs from durable outbox replay.
- Comparison with Kafka, transactional outbox, and Kafka outbox fallback.
- Real AWS/LocalStack validation is opt-in and outside the default build.

Diagrams:

- Architecture diagram: static component/boundary view with AWS SNS/SQS icons,
  layers, and a legend for local versus real AWS paths.
- Sequence diagram: publish -> fanout -> poll -> handler -> ack/retry/dead-letter
  with best-practices sequence styling.
- Both diagrams must pass the current `$bluetape4k-diagram` checklist and
  full-size PNG visual inspection.

## Acceptance Criteria

- `:aws-sqs-sns-coroutines:test` verifies publish mapping, consume handling,
  retry/failure classification, dead-letter classification, metrics/tags,
  validation, and cancellation propagation.
- `aws/README.md` and `aws/README.ko.md` list the module.
- `README.md` and `README.ko.md` list the module in the root AWS index.
- `.github/workflows/Examples.yml` and `scripts/smoke-validate.sh` run the
  module in the non-container AWS/smoke lane.
- Diagram QA evidence includes XML parse, CairoSVG render, connector audits,
  marker/color parity, sequence style audit, and full-size PNG eye inspection.
- PR metadata mirrors issue #327: assignee `debop`, milestone `1.3.1`, and
  issue labels.
