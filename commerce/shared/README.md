# Commerce Shared

[한국어](README.ko.md) | English

This repository-internal module contains compatibility contracts and fixtures
shared by commerce examples. It is intentionally not a production service,
persistence, adapter, or migration module.

## Provided contract

`VoucherCampaignBlackBoxContract` describes backend-neutral request,
normalized-result, replay, allocation, and lifecycle scenarios consumed by the
normalized-state and event-sourced voucher campaign examples.

The contract is available from:

```kotlin
io.bluetape4k.workshop.commerce.shared.voucher
```

Consumer compatibility tests should declare:

```kotlin
testImplementation(project(":commerce-shared"))
```

## Build and test

```bash
./gradlew :commerce-shared:test
./gradlew :commerce-shared:build
```

The module has no external infrastructure requirement.
