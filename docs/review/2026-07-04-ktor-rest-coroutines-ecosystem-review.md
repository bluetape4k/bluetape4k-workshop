# 7-Tier Review: ktor-rest-coroutines

Date: 2026-07-04
Module: `:ktor-rest-coroutines`
Scope: `$bluetape4k-code-patterns`, Kotlin style, and bluetape4k ecosystem reuse.

## Verdict

PASS: P0=0, P1=0.

## Findings

- P2: Route path parameter extraction used hand-written null checks with direct `IllegalArgumentException` construction. Replaced with `io.bluetape4k.support.requireNotNull` so Ktor examples use the same validation surface as the ecosystem.

## Ecosystem Reuse

- Kept existing `requireNotBlank`, `requireInRange`, bluetape4k coroutine/logging, and Jackson 3 integration patterns.
- Added bluetape4k null guard usage to path-id extraction without changing route behavior.

## Validation

- `./gradlew :ktor-rest-coroutines:test --console=plain`
- `git diff --check`
