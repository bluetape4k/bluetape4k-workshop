# Dependency Catalog Sync

## Context

`bluetape4k-dependencies` promoted MyBatis Dynamic SQL to 2.0.0, Timefold
Solver to 2.1.0, AWS SDK Java to 2.44.9, AWS SDK Kotlin to 1.6.77, and Fory
Kotlin to 0.17.0 as shared catalog versions.

## Decision

Materialize both shared catalog changes in the workshop repository without
touching unrelated local Windows wrapper drift in another workspace checkout.

## Outcome

`gradle/libs.versions.toml` now matches the central catalog for the promoted
dependencies.

## Verification

- `./gradlew build -x test --no-daemon`

The build completed with existing unrelated warnings.
