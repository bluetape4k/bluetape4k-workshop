# Issue #104 - Multi-Tenant Data Isolation Implementation Plan

**Date**: 2026-05-24
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/104
**Spec**: `docs/superpowers/specs/2026-05-24-issue-104-multi-tenant-data-isolation-design.md`
**Status**: Draft

## Tasks

- [x] Add `spring-boot/multi-tenant-data-isolation/build.gradle.kts`.
- [x] Add Spring Boot application entrypoint and H2/Exposed configuration.
- [x] Add `TenantId` and `InvoiceRecord` domain types with `Serializable` and `serialVersionUID`.
- [x] Add `InvoiceTable` and schema initializer.
- [x] Add `TenantInvoiceRepository : LongJdbcRepository<InvoiceRecord>`.
- [x] Add `UnsafeInvoiceRepository` baseline implementation.
- [x] Add `TenantKeyFactory`, tenant-keyed cache, per-key `ReentrantLock` lock registry, fixed-window in-memory rate limiter, and Micrometer counter service.
- [x] Add tests proving baseline leakage and tenant-safe behavior.
- [x] Add `src/test/resources/junit-platform.properties`.
- [x] Add `src/test/resources/logback-test.xml`.
- [x] Add `README.md` and `README.ko.md`.
- [x] Verify `./gradlew :spring-boot-multi-tenant-data-isolation:test`.
- [x] Verify `git diff --check`.
- [x] Audit English KDoc for new public APIs and README/README.ko.md lockstep.
- [x] Add concise lesson under `docs/lessons/`.
- [x] Run code review/advisor gates on the final diff.

## Implementation Notes

- Module path under `spring-boot/` creates project name `:spring-boot-multi-tenant-data-isolation` because `settings.gradle.kts` includes `spring-boot` with `withProjectName=false` and `withBaseDir=true`.
- Public API KDoc must be English.
- New tests use JUnit 5 plus `bluetape4k-assertions`; no AssertJ/JUnit assertion APIs.
- Data classes implement `Serializable` and define `serialVersionUID`.
- Tenant key helpers are intentionally explicit so README can contrast raw keys and tenant-prefixed keys.
- README claims must reference actual classes and dependencies only.
- Dependencies are pinned to existing aliases: `exposed-jdbc`, `bluetape4k-spring-boot4-core`, `bluetape4k-micrometer`, `micrometer-core`, `h2-v2`, Spring Boot JDBC/test starters, `bluetape4k-junit5`, and `bluetape4k-assertions`.
- Rate-limit and lock examples use in-memory state keyed by `TenantKeyFactory`; no new Bucket4j or distributed-lock dependency is introduced.

## Verification

```bash
./gradlew :spring-boot-multi-tenant-data-isolation:test
git diff --check
```

## Review Notes

- Claude advisor gate 1: `.omx/artifacts/claude-issue-104-design-20260524152918.md`
- P0/P1 findings from gate 1: fixed in this draft.
- Claude design rerun: `.omx/artifacts/claude-issue-104-design-rerun-20260524153255.md`, PASS, P0=0, P1=0.
- Claude code review: `.omx/artifacts/claude-issue-104-code-review-final-20260524154933.md`, PASS, P0=0, P1=0.
