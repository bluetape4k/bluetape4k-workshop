# Commerce Shared

[한국어](README.ko.md) | English

This repository-internal module contains compatibility contracts and fixtures shared by commerce examples. It is intentionally not a production service, persistence, adapter, or migration module.

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

`PayloadDigest` owns the usage-billing payload checksum contract. It delegates
to `TinkDigesters.SHA256` and preserves the lowercase 64-character hexadecimal
encoding of the UTF-8 payload. `AggregatePartitionKey` owns the deterministic
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
