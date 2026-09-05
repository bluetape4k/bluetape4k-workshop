# NATS JetStream Consumer Flow

[한국어](README.ko.md) | English

This module demonstrates the stable `bluetape4k-nats:2.0.0` cold Flow adapters
against a real JetStream-enabled NATS Testcontainer.

## What this example fixes at the consumer boundary

- Pull collection starts `ConsumerContext.iterate()` only when the Flow is collected.
- Push collection creates and owns one synchronous subscription per collection.
- The caller explicitly chooses `ack()`, `nak()`, or `term()` after business processing.
- `capacity`, pending-message limits, and pending-byte limits remain finite.
- A client pending-queue drop fails with `NatsConsumerFlowException`; it is never treated as success.
- Cancellation closes only the adapter-owned iterable consumer or subscription. The caller still owns
  the `Connection`, `JetStream`, and durable `ConsumerContext`.

## Bounded memory model

For push consumers, the upper bound is `pendingMessageLimit + capacity + 1` messages: the NATS client
pending queue, the Flow buffer, and the receiver's current message. Pull consumers request at most
`min(batchSize, capacity + 1)` messages. `NatsFlowLimits` keeps the workshop defaults explicit.

## Manual acknowledgement

| Decision | Call | Meaning |
|---|---|---|
| `ACK` | `message.ack()` | Processing completed successfully |
| `NAK` | `message.nak()` | Retryable failure; request redelivery |
| `TERM` | `message.term()` | Permanent failure; stop redelivery |

The adapter never makes this decision for the application.

## Run

```bash
./gradlew :messaging-nats-jetstream-flow:test --max-workers=1
```

The tests use `NatsServer.Launcher`, which starts NATS 2.14.4 with JetStream enabled (`-js`). They cover
pull/push ordering, cold handle creation, sequential cleanup, `ack`/`nak`/`term`, redelivery, and a real
pending-queue drop. No external credentials or live cluster are required.
