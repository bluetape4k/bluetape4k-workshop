# Issue #388 Code Pattern Ecosystem Cleanup

Date: 2026-07-03

## Context

The milestone 1.3.1 review asked for a pass over existing workshop code to ensure examples actively use bluetape4k ecosystem helpers instead of raw JDK or generic test APIs.

## Decision

Use narrow, source-backed substitutions:

- Opaque string ids use `Base58.randomString(8)` instead of embedding `UUID.randomUUID()`.
- Touched tests use `io.bluetape4k.assertions.assertFailsWith` instead of `kotlin.test.assertFailsWith`.
- Touched value-object validation uses `bluetape4k.support.require*` helpers.

## Outcome

`spring-modulith-ddd-order-audit` and `kotlin-flow-extensions-parallel-enrichment` now align with the code-pattern skill for the residual findings in issue #388.

## Future Guard

Before changing raw Testcontainers usage, check whether the test intentionally needs an isolated container, a custom Docker network, a network alias, or an explicit failure mode. Record the exception when a launcher singleton would weaken the test.

## Verification

- Pattern grep for raw UUID/test assertion imports in the affected modules returned no matches.
- Targeted compile passed for `:spring-modulith-ddd-order-audit` and `:kotlin-flow-extensions-parallel-enrichment`.
- Targeted tests passed: 15 Spring Modulith tests and 6 Flow enrichment tests.
- `git diff --check` passed.
