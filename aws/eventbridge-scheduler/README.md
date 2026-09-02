# AWS EventBridge Scheduler Workshop

[한국어](README.ko.md) | English

This module shows how an order workflow can be mapped to two AWS integration boundaries:
an Amazon EventBridge event envelope and a delayed EventBridge Scheduler request. The default
implementation is local-first, so tests and examples run without AWS credentials.

## Architecture

![AWS EventBridge Scheduler architecture](../../docs/images/readme-diagrams/aws-eventbridge-scheduler-readme-architecture-01.png)

The example keeps the workshop boundary explicit. `OrderWorkflowService` validates the learner
request, creates an AWS SDK v2 `PutEventsRequestEntry`, and then creates a scheduler request only
after the EventBridge publish boundary succeeds. Local adapters capture both requests in memory;
they are the seam where a real AWS client can be introduced later.

## Sequence

![AWS EventBridge Scheduler sequence](../../docs/images/readme-diagrams/aws-eventbridge-scheduler-readme-sequence-01.png)

The sequence highlights the important operational contract: EventBridge failure stops the delayed
schedule, while Scheduler failure still reports that the event envelope was published. Cancellation
is rethrown before broad exception handling so coroutine cancellation remains cooperative.

## What To Compare

| pattern | use when | trade-off |
| --- | --- | --- |
| Local application event | The next handler lives inside the same process. | Simple, but no cloud routing, SaaS target, or delayed invocation boundary. |
| Kafka outbox | The workflow needs durable replay and consumer-owned processing. | Strong persistence story, but learners must reason about database relay and topic consumers. |
| EventBridge event | The workflow should become an AWS-native event that can route to many targets. | Good service-bus boundary, but delivery and target permissions become AWS concerns. |
| EventBridge Scheduler | A delayed or time-based invocation is part of the workflow contract. | Clear schedule semantics, but idempotency keys and target payloads must be designed carefully. |

## Key Classes

| class | responsibility |
| --- | --- |
| `OrderWorkflowService` | Validates input, builds the EventBridge entry, calls the publish boundary, and schedules delayed work only after publish success. |
| `EventBridgePublisher` | Boundary interface for publishing `PutEventsRequestEntry` values. The default `LocalEventBridgePublisher` stores entries in memory. |
| `WorkflowScheduler` | Boundary interface for delayed workflow requests. The default `LocalWorkflowScheduler` stores requests in memory. |
| `OrderWorkflowProperties` | Maps workshop configuration to source, detail type, bus name, scheduler group, target ARN, and flexible window mode. |

## Run

```bash
./gradlew :aws-eventbridge-scheduler:test
./gradlew :aws-eventbridge-scheduler:bootRun
```

## Notes

`bluetape4k-dependencies` 2.0.0 resolves `bluetape4k-aws` 1.0.0. Its Spring Boot artifact exposes
EventBridge operations when the AWS SDK module is present, while Scheduler remains a direct AWS SDK
concern. This workshop keeps the AWS SDK v2 EventBridge/Scheduler models plus local boundary
interfaces to make the scheduler contract explicit.

Do not put raw secrets or sensitive personal data in EventBridge detail payloads. Keep the example
payload limited to workflow identifiers, correlation IDs, and learner-safe reason text.
