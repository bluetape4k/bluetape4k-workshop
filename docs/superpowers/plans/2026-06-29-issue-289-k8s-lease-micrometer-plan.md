# Issue #289 - Kubernetes Lease Micrometer Workshop Plan

**Date**: 2026-06-29
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/289
**Spec**: `docs/superpowers/specs/2026-06-29-issue-289-k8s-lease-micrometer-design.md`
**Module**: `leader/k8s-lease-micrometer` -> `:leader-k8s-lease-micrometer`
**Status**: Approved by user

## T1 - Build and Catalog

- Add versionless aliases for `bluetape4k-leader-k8s`,
  `bluetape4k-leader-micrometer`, and `fabric8-kubernetes-client`.
- Add the Spring Boot 4 module build with actuator, Micrometer core, Prometheus
  registry, Fabric8 client, bluetape4k logging, coroutines, and deterministic
  test dependencies.
- Do not add a `bluetape4k-leader` BOM or explicit bluetape4k versions.

**DoD**: dependency resolution succeeds and the module is listed by
`./gradlew projects`.

## T2 - Tests First

- Add properties validation tests for identity, namespace, lease name, and
  duration ordering.
- Add metrics tests with `SimpleMeterRegistry` to lock meter names/tags.
- Add guarded-task tests with fake coordinators for elected, skipped, and
  failing execution paths.

**DoD**: tests initially fail because production classes are absent, then pass
after implementation.

## T3 - Implementation

- Implement `K8sLeaseMicrometerProperties`.
- Implement `LeaderCoordinator`, default disabled coordinator, and opt-in
  Kubernetes coordinator.
- Implement `K8sLeaseMetrics` and `K8sLeaseGuardedTask`.
- Implement Spring configuration with `@ConditionalOnProperty` so default tests
  avoid Kubernetes.

**DoD**: targeted module tests pass with no real Kubernetes cluster.

## T4 - Documentation and Diagrams

- Add `README.md` and `README.ko.md` with language switches.
- Add Top-to-Bottom architecture and sequence diagrams as SVG+PNG.
- Document kind/RBAC manifests, run commands, expected leader handoff behavior,
  metric names/tags, default local behavior, and production boundaries.
- Update root README/README.ko module catalog and smoke validator count.

**DoD**: README parity and language validators pass; image links resolve.

## T5 - Verification, Review, PR, CI

- Run targeted tests, compile with warnings, project listing, README validators,
  diagram asset checks, and `git diff --check`.
- Perform review pass and record a short lesson if the implementation or
  workflow reveals durable guidance.
- Commit with Lore trailers, push, create PR with issue metadata mirrored, and
  verify live PR metadata and CI.

**DoD**: PR has assignee `debop`, milestone `1.2.0`, issue labels mirrored,
body ending in `## DoD Status`, and CI evidence is collected.
