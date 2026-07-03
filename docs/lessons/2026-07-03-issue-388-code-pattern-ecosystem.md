# Issue #388 Code Pattern Ecosystem Cleanup

Date: 2026-07-03

## Context

The milestone 1.3.1 review asked for a pass over existing workshop code to ensure examples actively use bluetape4k ecosystem helpers instead of raw JDK or generic test APIs.

## Decision

Use narrow, source-backed substitutions first, then run a repository-wide scan before declaring the cleanup complete:

- Opaque string ids use `Base58.randomString(8)` instead of embedding `UUID.randomUUID()`.
- Touched tests use `io.bluetape4k.assertions.assertFailsWith` instead of `kotlin.test.assertFailsWith`.
- Touched value-object validation uses `bluetape4k.support.require*` helpers.
- Production null assertions use `requireNotNull` / `requireNotBlank` for caller-facing values and `checkNotNull` for persistence, observation, and singleton invariants.
- Runtime debug output uses bluetape4k lazy logging instead of `println`.

## Outcome

The original issue modules plus the broader safe-production sweep now align with the code-pattern skill for UUID generation, touched assertions, touched validation, production `!!`, runtime `println`, and stale commented examples.

## Future Guard

Before changing raw Testcontainers usage, check whether the test intentionally needs an isolated container, a custom Docker network, a network alias, or an explicit failure mode. Record the exception when a launcher singleton would weaken the test.

Do not claim a repo-wide code-pattern cleanup from a narrow grep. At minimum, scan all tracked Kotlin files for `!!`, raw `require`, `Thread.sleep`, `runBlocking`, raw Testcontainers constructors, legacy assertion imports, and raw UUID generation. Split behavior-sensitive examples such as virtual-thread sleeps, blocking-to-suspend bridges, and test assertion rewrites into focused issues; this pass created #390, #391, and #392.

## Verification

- Repository scan covered 1,473 Kotlin files. After fixes, production `!!`, raw `GenericContainer`, legacy assertion imports, and raw UUID generation are 0.
- Targeted compile passed for 11 touched modules.
- Targeted tests passed for 11 touched modules in one serial Gradle run.
- `git diff --check` passed.
