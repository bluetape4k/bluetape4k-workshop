# Issue #290 - JaVers Persistence Audit Workshop Plan

**Date**: 2026-06-29
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/290
**Spec**: `docs/superpowers/specs/2026-06-29-issue-290-javers-persistence-audit-design.md`
**Module**: `exposed/javers-persistence-audit` -> `:exposed-javers-persistence-audit`
**Status**: Approved by user

## T1 - TDD Red

- Add module build, test resources, and tests before production source.
- Lock behavior for Redis-backed commit persistence, history query, latest
  snapshot, diff query, and audit sink failure.
- Run `./gradlew :exposed-javers-persistence-audit:test` and confirm it fails
  because production classes are absent.

**DoD**: red test failure is caused by unresolved production symbols.

## T2 - Implementation

- Add immutable `Order` aggregate, `OrderStatus`, `OrderTable`, and
  `OrderAuditService`.
- Add `RedisOrderAuditFactory` that builds a JaVers instance with
  `RedissonCdoSnapshotRepository`.
- Use bluetape4k validation helpers for caller input.
- Keep the Exposed table as current-state storage and Redis-backed JaVers as
  audit snapshot storage.

**DoD**: targeted module tests pass.

## T3 - Documentation and Diagrams

- Add README.md and README.ko.md with backend selection guidance:
  in-memory vs Redis vs Kafka.
- Generate SVG+PNG architecture and write-order flow diagrams one at a time.
- Use official/catalog Redis, Kafka, and database icons directly.
- Run the `$bluetape4k-diagram` fast failure checklist, helper audits, CairoSVG
  rendering, contact sheet inspection, and full-size PNG eye inspection.

**DoD**: README validators pass and every touched PNG is visually inspected.

## T4 - Registration and CI

- Add root README/README.ko index rows.
- Add workflow path filters and container-backed lane coverage.
- Update `scripts/smoke-validate.sh` stale module count and data-access-full
  coverage without slowing unrelated smoke examples.

**DoD**: `./gradlew projects`, `actionlint`, and stale-check pass.

## T5 - Review, Commit, PR

- Run targeted compile/tests, dependency resolution, README/diagram validators,
  `git diff --check`, and local 7-tier review.
- Commit with Lore protocol.
- Create PR assigned to `debop`, milestone `1.2.0`, labels mirrored from issue
  #290, and final `## DoD Status` section.

**DoD**: live PR metadata/body verified and CI checks pass.
