# Issue #289 - Kubernetes Lease Micrometer Workshop Design

**Date**: 2026-06-29
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/289
**Milestone**: 1.2.0
**Status**: Ready for implementation

## Goal

Add a `leader/k8s-lease-micrometer` workshop module that teaches Kubernetes
Lease leader election with Micrometer metrics without requiring a Kubernetes
cluster for the default build.

Learners should understand:

- how a Spring Boot 4 service maps identity, namespace, lease duration, wait
  time, retry delay, and scheduler interval into `KubernetesLeaseOptions`;
- how a guarded coroutine task executes only on the elected instance;
- which Micrometer meters describe leadership state, renew heartbeats, renew
  failures, skipped ticks, and task execution;
- how to opt into a real Kubernetes client for kind/K3s/manual runs;
- the RBAC permissions required for `coordination.k8s.io/v1` Lease objects.

## Source Evidence

| Source | Evidence |
|--------|----------|
| GitHub issue #289 | Requires Kubernetes Lease leadership, Micrometer metrics, deterministic default tests, opt-in Kubernetes boundary, RBAC docs, and README locale parity. |
| `leader-k8s` source | Provides `KubernetesLeaseOptions`, `KubernetesLeaseSuspendLeaderElector`, and `KubernetesClient.suspendRunIfLeader(...)`. |
| `leader-micrometer` source | Provides decorator metrics such as `shedlock.leader.acquired`, `shedlock.leader.not_acquired`, `shedlock.leader.duration`, and `shedlock.leader.active`. |
| `examples/k8s-lease` in `bluetape4k-leader` | Uses Fabric8 typed `coordination.k8s.io/v1` Lease API and tags real K3s tests as opt-in. |
| `examples/prometheus-dashboard` in `bluetape4k-leader` | Demonstrates explicit Micrometer pre-registration and Prometheus actuator exposure. |
| Workshop repo rules | New modules need README/README.ko parity, generated PNG/SVG diagrams, validation matrix updates, and smoke-safe tests when added to normal CI. |

## Non-Goals

- Do not require kind, K3s, minikube, or a live Kubernetes API for
  `:leader-k8s-lease-micrometer:test`.
- Do not replace the Redis or ZooKeeper leader examples.
- Do not introduce an individual `bluetape4k-leader` BOM or explicit
  bluetape4k module versions.
- Do not implement a custom Kubernetes Lease algorithm in the workshop. The
  opt-in real path delegates to `bluetape4k-leader-k8s`.
- Do not promise production hardening such as Pod security policy, retry
  budgets, multi-cluster failover, or admission-control policy.

## Design

### Module

```
leader/k8s-lease-micrometer/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/leader/k8slease/
    K8sLeaseMicrometerApp.kt
    config/K8sLeaseMicrometerConfig.kt
    config/K8sLeaseMicrometerProperties.kt
    leader/LeaderCoordinator.kt
    job/K8sLeaseGuardedTask.kt
    metrics/K8sLeaseMetrics.kt
  src/main/resources/application.yml
  src/test/kotlin/io/bluetape4k/workshop/leader/k8slease/
    config/K8sLeaseMicrometerPropertiesTest.kt
    job/K8sLeaseGuardedTaskTest.kt
    metrics/K8sLeaseMetricsTest.kt
```

The Gradle project is auto-registered by `includeModules("leader", false, true)`
as `:leader-k8s-lease-micrometer`.

### Runtime Model

`LeaderCoordinator` is a small application boundary:

- `DisabledLeaderCoordinator` is the default and always skips. It lets the
  Spring context and all tests run without Kubernetes credentials.
- `KubernetesLeaderCoordinator` is created only when
  `workshop.leader.k8s.enabled=true`. It owns the Fabric8 client and wraps a
  `KubernetesLeaseSuspendLeaderElector`.

The scheduled service calls:

1. record a guard attempt;
2. call `runIfLeader(lockName)`;
3. when elected, mark leadership active, run a deterministic coroutine task, and
   record task duration/execution count;
4. when skipped, increment the skipped counter;
5. always reset the JVM-local active gauge after the elected block finishes.

### Metrics

Workshop meters:

| Meter | Type | Tags | Meaning |
|-------|------|------|---------|
| `workshop.k8s.lease.leader.active` | gauge | `lock.name`, `namespace` | JVM-local active leader task state. |
| `workshop.k8s.lease.guard.attempts` | counter | `lock.name`, `namespace` | Scheduled guard attempts. |
| `workshop.k8s.lease.guard.skipped` | counter | `lock.name`, `namespace`, `reason` | Attempts that did not execute the task. |
| `workshop.k8s.lease.renew.attempts` | counter | `lock.name`, `namespace` | Demonstration heartbeat emitted while elected work is active. |
| `workshop.k8s.lease.renew.failures` | counter | `lock.name`, `namespace`, `reason` | Demonstration heartbeat failures surfaced by the task boundary. |
| `workshop.k8s.lease.task.executions` | counter | `lock.name`, `namespace`, `result` | Guarded task outcomes. |
| `workshop.k8s.lease.task.duration` | timer | `lock.name`, `namespace` | Guarded task duration. |

The README also names the upstream `leader-micrometer` decorator meters so
learners can distinguish application-level workshop meters from library-level
instrumentation.

### Kubernetes Boundary

The real Kubernetes path is opt-in:

```yaml
workshop:
  leader:
    k8s:
      enabled: true
      namespace: workshop
      identity: orders-api-1
      lease-name: orders-nightly-export
```

Required RBAC verbs for the Lease resource:

- `get`
- `list`
- `watch`
- `create`
- `update`
- `patch`
- `delete`

Default tests do not use those permissions and do not touch a cluster.

## Validation

- `./gradlew :leader-k8s-lease-micrometer:test`
- `./gradlew :leader-k8s-lease-micrometer:compileTestKotlin --warning-mode all`
- `./gradlew projects`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-language.mjs`
- `git diff --check`
