# Leader Backend Comparison Lab

[한국어](README.ko.md) | English

This module compares Redis, ZooKeeper, and Kubernetes Lease leader-election
backends with a deterministic Spring Boot 4 lab.

It does not replace the runnable backend-specific examples. Use this module to
choose a backend and understand failover semantics, then move to the real
practice modules:

- [leader-election](../leader-election/) for Redis + Lettuce.
- [leader-zookeeper](../leader-zookeeper/) for ZooKeeper + Curator.
- [k8s-lease-micrometer](../k8s-lease-micrometer/) for Kubernetes Lease +
  Micrometer.

## Architecture

![Leader backend comparison lab architecture](../../docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.png)

`LeaderBackendCatalog` keeps source-backed profiles for each backend.
`LeaderFailoverLab` turns those profiles into deterministic reports for steady
leader execution, follower skip, action failure, and backend-loss handoff. The
default tests do not start Redis, ZooKeeper, Kubernetes, LocalStack, or any
other infrastructure.

## Scenario Flow

![Leader backend comparison lab sequence](../../docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.png)

1. A scheduled tick reaches every candidate.
2. The lab models `runIfLeader(report-sync)` for the selected backend profile.
3. The backend profile explains whether leadership is acquired or skipped.
4. One node executes the guarded job; followers receive a skip report.
5. The backend-loss branch shows the backend-specific handoff trigger.
6. The report names the metrics and events that learners should inspect in the
   real practice module.

## Backend Selection Matrix

| Backend | Status | Primitive | Failover trigger | Tuning surface | Practice module |
|---------|--------|-----------|------------------|----------------|-----------------|
| Redis + Lettuce | Stable | Redis key with lease TTL | Lease TTL expiry or explicit release | `waitTime`, `leaseTime`, `autoExtend` | [`leader-election`](../leader-election/) |
| ZooKeeper + Curator | Stable | Ephemeral znode / Curator mutex | ZooKeeper session loss | `sessionTimeoutMs`, `connectionTimeoutMs`, `groupMaxLeaders` | [`leader-zookeeper`](../leader-zookeeper/) |
| Kubernetes Lease | Preview opt-in | `coordination.k8s.io/v1` Lease object | Lease expiry and resource-version update | `namespace`, `identity`, `leaseTime`, `retryDelay`, `autoExtend` | [`k8s-lease-micrometer`](../k8s-lease-micrometer/) |

Use Redis when the service already operates Redis and wants a fast TTL-backed
guard for scheduled work. Use ZooKeeper when session-bound ownership or group
leadership is a better fit. Use Kubernetes Lease for Kubernetes-native workloads
that can grant Lease RBAC and expose Micrometer metrics.

## Failover Lab Scenarios

| Scenario | What it teaches | Local behavior |
|----------|-----------------|----------------|
| `steady-leader` | One elected node runs the guarded job. | `node-a` executes `report-sync`; `node-b` skips. |
| `contention-skip` | Followers must not duplicate a scheduled job. | One contender executes; the remaining contenders record skip events. |
| `action-failure-release` | A failed elected action should not hide the next eligible run. | Failure is recorded, ownership is released or expires, and the next candidate can run. |
| `backend-loss-handoff` | Handoff cause differs by backend. | Redis uses lease expiry, ZooKeeper uses session loss, Kubernetes uses Lease expiry/resource update. |

## Metrics And Events

| Backend | What to inspect |
|---------|-----------------|
| Redis + Lettuce | `LeaderElectionEvent` Flow, listener callbacks, skip/elected/revoked events. |
| ZooKeeper + Curator | Single-leader result, group-leader slot result, Curator connection state. |
| Kubernetes Lease | `leader-micrometer` meters, `workshop.k8s.lease.*` meters, Prometheus scrape. |

These rows are learning guidance, not production benchmark rankings. Production
choice still depends on existing infrastructure, availability requirements,
failure domains, RBAC, operational skill, and idempotency of the guarded work.

## Run Locally

```bash
./gradlew :leader-backend-comparison-lab:test
./gradlew :leader-backend-comparison-lab:bootRun
```

The test path is deterministic and infrastructure-free. It is safe for smoke
validation and can run without Docker, kubeconfig, service-account credentials,
or networked backend services.

## Test Map

| Test class | What it protects |
|------------|------------------|
| `LeaderBackendCatalogTest` | Backend IDs, status, failover triggers, metrics/events, practice-module links, and unknown-ID validation. |
| `LeaderFailoverLabTest` | Scenario ordering, follower skip reports, action-failure recovery, and backend-specific handoff summaries. |

## Production Boundaries

This is a workshop comparison lab. Production services still need graceful
shutdown, idempotent guarded work, alerting, backend health checks, retry policy,
credential/RBAC review, capacity planning, and a real failover exercise against
the chosen backend.

## Dependencies

```kotlin
implementation(libs.bluetape4k.core)
implementation(libs.bluetape4k.leader.core)
implementation(libs.bluetape4k.logging)
implementation(libs.spring.boot.starter.actuator)
testImplementation(libs.bluetape4k.assertions)
testImplementation(libs.bluetape4k.junit5)
```
