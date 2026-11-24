# Commerce Shared

[한국어](README.ko.md) | English

This repository-internal module contains compatibility contracts and fixtures shared by commerce examples. It is intentionally not a production service, persistence, adapter, or migration module.

## Business requirements and test evidence

The shared boundary exists to prevent producer and consumer contracts from
drifting while keeping each example's database and delivery authority local.
The source design is
`docs/superpowers/specs/2026-08-16-issue-573-commerce-shared-boundary-design.md`,
with the payload digest clarification in
`docs/superpowers/specs/2026-11-17-issue-1044-commerce-shared-digest-design.md`.

| Requirement | This module owns | Deliberately does not own |
|---|---|---|
| Voucher examples compare the same business scenarios | `VoucherCampaignBlackBoxContract` request/result/replay/lifecycle fixtures | A database, campaign state machine, HTTP adapter, or transport |
| Usage-billing services agree on accidental-corruption detection | `PayloadDigest` canonical UTF-8 SHA-256 and `AggregatePartitionKey` format | Authentication: a digest is not a MAC/signature |
| Contract tests remain independent | Provider-neutral fixtures and an independent JDK digest oracle | Hidden shared runtime behavior that could make both sides repeat the same bug |

`CommerceIntegrationContractsTest` and `VoucherCampaignBlackBoxContractTest`
are the executable map: they prove canonical inputs, digest format, partition
key stability, and normalized/event-sourced scenario compatibility. Consumer
integration tests must still prove their own PostgreSQL, Kafka, idempotency, and
recovery behavior; a green shared test is not evidence that a service is durable.

## Why this module exists

The voucher and usage-billing examples need to agree on event identity, replay
inputs, partition keys, and payload digests while still owning their own
application behavior. Duplicating those rules in each example would make a
producer and consumer appear compatible while their edge cases drift.

This module therefore supplies the smallest provider-neutral contracts and
fixtures that can be checked from both sides. It has no database, schema,
transport, or state-transition authority; the examples remain responsible for
their own persistence and delivery boundaries. The stateless helpers and
independent test oracles are deliberate design choices: they make contract
drift visible without turning a test fixture into a hidden shared runtime.

The `:commerce-shared:test` and `:commerce-shared:build` commands below are the
test-backed verification for this boundary. They prove the shared contract and
fixture behavior without external infrastructure; they do not prove the
durability or delivery behavior of any consuming service.

## Provided contract

`VoucherCampaignBlackBoxContract` describes backend-neutral request, normalized-result, replay, allocation, and lifecycle scenarios consumed by the normalized-state and event-sourced voucher campaign examples.

The contract is available from:

```kotlin
io.bluetape4k.workshop.commerce.shared.voucher
```

Consumer compatibility tests should declare:

```kotlin
testImplementation(project(":commerce-shared"))
```

## Integration contracts

`PayloadDigest` owns the usage-billing payload checksum contract. It uses
`Digesters.SHA256.digestHexUtf8` and `matchesHexUtf8` from `bluetape4k-io` to
preserve the lowercase 64-character hexadecimal encoding of the UTF-8 payload.
`AggregatePartitionKey` owns the deterministic
`tenantId|aggregateType|aggregateId` key shared by Kafka partitioning and local
outbox/inbox boundaries.

These helpers are stateless and do not own a schema, database, or transport.
The checksum detects accidental corruption and contract drift; it is not
authentication. Use a MAC or signature when hostile tampering is in scope.

Usage-billing services should depend on this module instead of calling the
provider directly, while their independent JDK SHA-256 test oracles remain
local to verify the shared contract.

## Build and test

```bash
./gradlew :commerce-shared:test
./gradlew :commerce-shared:build
```

The module has no external infrastructure requirement.
