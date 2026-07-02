# Issue 326 EventBridge Scheduler Design

## Context

Issue #326 adds a local-first AWS-native workflow example for `bluetape4k-workshop`.
The workshop already has application events, Kafka, transactional outbox, and AWS
storage/observability examples, but it does not show when to choose EventBridge
and a Scheduler-style delayed workflow boundary.

Current source evidence:

- `bluetape4k-aws` `develop` source has EventBridge support, but
  `bluetape4k-dependencies 1.3.1` resolves `bluetape4k-aws-spring-boot:0.4.0`,
  whose artifact does not expose the EventBridge Spring package yet.
- `bluetape4k-aws` Scheduler wrapper support is still tracked separately by
  `bluetape4k-aws` issue #310, so this workshop must not imply a finished
  bluetape4k Scheduler integration.
- `bluetape4k-workshop` imports `bluetape4k-dependencies` at the root; new
  workshop modules must not pin bluetape4k versions.

## Goal

Create `aws/eventbridge-scheduler` as a learner-facing Spring Boot example that
maps an order workflow request into:

1. An EventBridge `PutEventsRequestEntry`.
2. A local Scheduler-style request boundary.
3. A report that preserves idempotency key and correlation ID across both
   external boundaries.

Default tests must run without real AWS credentials.

## Non-Goals

- Do not implement real AWS Scheduler support before `bluetape4k-aws` issue #310
  provides the wrapper.
- Do not add a Kafka outbox implementation inside this module.
- Do not require LocalStack or real AWS for default verification.
- Do not add a new bluetape4k version pin.

## Architecture

The module uses a small Spring Boot service:

- `OrderWorkflowService` owns the use case.
- `EventBridgePublisher` is a workshop-local boundary over AWS SDK v2
  `PutEventsRequestEntry` so the example can teach the envelope without waiting
  for a newer bluetape4k-aws artifact.
- `WorkflowScheduler` is a workshop-local boundary that models the Scheduler
  request contract and is backed by a local capturing implementation for tests
  and default runtime.
- `OrderWorkflowProperties` holds EventBridge source/detail type/event bus and
  Scheduler group/target defaults.

The example intentionally separates EventBridge routing from Scheduler delayed
execution. EventBridge publishes the domain event envelope. Scheduler stores the
delayed callback intent. Both use the same idempotency key and correlation ID so
learners can reason about deduplication and tracing.

## Data Contract

`OrderWorkflowRequest` fields:

- `orderId`: required nonblank.
- `customerId`: required nonblank.
- `workflow`: enum such as `PAYMENT_REMINDER` or `FULFILLMENT_CHECK`.
- `scheduledAt`: required future or explicit timestamp; tests use fixed
  `Instant` values.
- `idempotencyKey`: required nonblank.
- `correlationId`: required nonblank.
- `reason`: optional nonblank after trimming.

`EventBridgeWorkflowEnvelope` contains source, detail type, event bus name, JSON
detail, idempotency key, correlation ID, and event time.

`SchedulerWorkflowRequest` contains schedule name, group name, target ARN,
schedule expression, payload JSON, flexible time window mode, idempotency key,
and correlation ID.

## Failure Contract

- Validation failures throw `IllegalArgumentException` through bluetape4k
  validation helpers.
- `CancellationException` is always rethrown.
- EventBridge failure marks the EventBridge side as `FAILED` and skips Scheduler
  scheduling to avoid scheduling a workflow whose routing event was not accepted.
- Scheduler failure keeps EventBridge as `PUBLISHED` and marks Scheduler as
  `FAILED`; the report includes the failure message.

## Documentation Contract

`README.md` and `README.ko.md` must explain:

- The local-first default.
- EventBridge vs Scheduler responsibility split.
- Comparison with local application events, Kafka outbox, EventBridge, and
  Scheduler.
- How to opt into real AWS later, with a warning that Scheduler wrapper support
  is not part of this module yet.

Diagrams:

- Architecture diagram: static component/boundary view.
- Sequence diagram: request -> EventBridge publish -> Scheduler request, with
  failure/skip branch.
- Both diagrams must pass the current `$bluetape4k-diagram` checklist and
  full-size PNG visual inspection.

## Acceptance Criteria

- `:aws-eventbridge-scheduler:test` verifies envelope mapping, schedule request
  mapping, idempotency/correlation propagation, validation, failure handling,
  and cancellation propagation.
- New module is included by `settings.gradle.kts` auto-discovery.
- `aws/README.md` and `aws/README.ko.md` list the module.
- `README.md` and `README.ko.md` include the module in the root index when the
  repo index already lists AWS examples.
- `.github/workflows/Examples.yml` and `scripts/smoke-validate.sh` run the
  module in the non-container AWS/smoke lane.
- Diagram validators include the new architecture and sequence assets.
- PR metadata mirrors issue #326: assignee `debop`, milestone `1.3.1`, and
  relevant labels.
