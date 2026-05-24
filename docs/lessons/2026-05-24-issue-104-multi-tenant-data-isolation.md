# Issue #104 Multi-Tenant Data Isolation

## Context

GitHub issue #104 requested an advanced workshop example proving tenant isolation across data access, cache keys, locks, rate limits, and metrics.

## Decision

Add `spring-boot/multi-tenant-data-isolation` as a deterministic Spring Boot + H2 example. Use Bluetape4k Exposed JDBC `LongJdbcRepository` for the safe repository path, and keep lock/rate-limit examples in memory so the lesson focuses on tenant-key design rather than external infrastructure.

## Outcome

The module includes safe and unsafe repository/cache paths, tenant-prefixed key helpers, per-key locks, tenant-keyed rate limiting, tenant-tagged Micrometer counters, and English/Korean README files.

## Verification

- `./gradlew :spring-boot-multi-tenant-data-isolation:test` passed.
- `./gradlew projects` includes `:spring-boot-multi-tenant-data-isolation`.
- `git diff --check` passed.

## Future Guidance

When adding Spring Boot workshop modules under `spring-boot/`, the Gradle project name includes the base directory prefix, for example `:spring-boot-<module>`.
