# Kinesis Coroutines Workshop

[한국어](README.ko.md) | English

This module demonstrates the `bluetape4k` AWS Spring Boot 0.5.0 Kinesis
coroutine contract with a credential-free local default. The local adapter is
an in-memory, one-stream/one-shard fake; it does not create an AWS client,
resolve credentials, start a polling job, or access the network.

## Profiles and commands

| Profile | Default behavior | Command |
| --- | --- | --- |
| `local` (default) | Deterministic `LocalKinesisOperations`; demo publishes and consumes three records | `./gradlew :aws-kinesis-coroutines:bootRun` |
| `real-aws` (explicit opt-in) | Upstream `KinesisCoroutinesTemplate`; `run-demo` is false unless explicitly enabled | `AWS_REGION=ap-northeast-2 ./gradlew :aws-kinesis-coroutines:bootRun --args='--spring.profiles.active=real-aws --kinesis.workshop.run-demo=true --kinesis.workshop.stream-name=kinesis-workshop-<unique>'` |

The local command should print a redacted summary similar to:

```text
Kinesis demo completed: publishedCount=3, consumedCount=3, sequenceCount=3
```

The process exits with code `0` (exit code `0`) after a successful local demo. The summary
contains counts and sequence metadata only; it does not contain payloads,
partition keys, endpoints, credentials, or raw exception messages.

## AWS safety boundary

`real-aws` is intentionally opt-in. Supply `AWS_REGION` and the standard AWS
credential provider chain yourself. Use a unique stream name and review every
command before running it: the module can create and write a real Kinesis
stream, and it does not delete that stream automatically. The minimum demo
permissions are:

```text
kinesis:CreateStream
kinesis:DescribeStream
kinesis:PutRecord
kinesis:GetShardIterator
kinesis:GetRecords
```

The stream can incur AWS charges. After reviewing the target, delete it
explicitly when appropriate:

```bash
aws kinesis delete-stream --stream-name "$KINESIS_WORKSHOP_STREAM_NAME"
```

The optional endpoint override is restricted to HTTP(S) loopback hosts and the
fixed local service names `localstack` and `kinesis`. URI user-info, link-local
metadata hosts, and arbitrary private hosts are rejected. Never put access
keys, secret keys, session tokens, or payloads in source, YAML, logs, or
commands.

## What the example teaches

- `ensureStream` describes first, creates only after `ResourceNotFound`, and
  waits at most 30 seconds for `ACTIVE` with a 250 ms readiness poll.
- `publish` serializes a bounded JSON event and preserves its partition key at
  the upstream request boundary.
- `consume` returns a cold `Flow`; collection controls polling, backpressure,
  and cancellation. `take(3)` does not close the shared client.
- Iterator-expiration and throttling retry budgets belong to the upstream
  `KinesisCoroutinesTemplate`; the module does not reimplement that contract.
- Metrics are limited to `kinesis.workshop.publish`, `.consume`, `.retry`, and
  `.failure`, with only `backend`, `operation`, and `outcome` tags. Health is
  `UP` for local, `UNKNOWN` for an unready real-AWS demo, and `DOWN` after a
  terminal failure.
- Shutdown has a 10-second lifecycle bound: app-owned demo jobs finish first,
  caller-owned collectors are passively observed, and owned AWS resources are
  cleaned up afterward. A caller-owned collector is never cancelled by the
  registry.

Records appended to the same local shard are deterministic and ordered for
this exercise. Kinesis does not provide a global ordering guarantee across
shards or producers, and this module does not claim exactly-once delivery.

## Tests

Run the module without AWS credentials or Docker:

```bash
./gradlew :aws-kinesis-coroutines:test --no-daemon --max-workers=1
```

The tests cover property bounds, endpoint redaction, the deterministic fake,
stream readiness, cold Flow cancellation, the public upstream template
contract, profile bean selection, runner output, metrics, health, and passive
shutdown registration.
