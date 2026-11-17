# Commerce Shared

[한국어](README.ko.md) | English

This repository-internal module contains compatibility contracts and fixtures shared by commerce examples. It is intentionally not a production service, persistence, adapter, or migration module.

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
