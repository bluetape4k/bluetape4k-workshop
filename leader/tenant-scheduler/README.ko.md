# Tenant-Scoped Leader Scheduler Lab

[English](README.md) | 한국어

이 모듈은 tenant-scoped leader scheduling을 결정론적인 Spring Boot 4 lab으로
학습합니다. Production scheduler 안에 숨어서 놓치기 쉬운 부분을 앞으로
꺼냅니다. tenant마다 독립된 leader lock name이 있어야 하고, tick마다 공정한
선택 순서가 있어야 하며, stale lock handoff와 metric tag cardinality도 별도로
다뤄야 합니다.

실제 backend를 붙이기 전에 이 lab으로 scheduling contract를 먼저 고정합니다.
그 다음 production-facing 연습은 다음 모듈에서 이어가면 됩니다.

- [leader-election](../leader-election/): Redis + Lettuce leader election.
- [leader-zookeeper](../leader-zookeeper/): ZooKeeper + Curator ownership.
- [k8s-lease-micrometer](../k8s-lease-micrometer/): Kubernetes Lease와
  Micrometer metric.
- [backend-comparison-lab](../backend-comparison-lab/): backend 선택 기준.
- [spring-boot/multi-tenant-data-isolation](../../spring-boot/multi-tenant-data-isolation/):
  tenant data boundary 사고 연습.

## Architecture

![Tenant scheduler architecture](../../docs/images/readme-diagrams/leader-tenant-scheduler-readme-architecture-01.png)

`TenantSchedulePolicy`는 job, tenant alias, tick capacity, stale lease window,
bounded event history를 검증합니다. `TenantLockNamePlanner`는
`TenantLockNamespace`를 사용하므로 `tenant-a`와 `tenant-b`가 같은
`invoice-sync` leader lock을 공유하지 않습니다. `TenantSchedulerLab`은 순수한
logical-tick reducer입니다. Redis, ZooKeeper, Kubernetes, background scheduler를
시작하지 않습니다.

## Sequence

![Tenant scheduler sequence](../../docs/images/readme-diagrams/leader-tenant-scheduler-readme-sequence-01.png)

1. logical tick이 due tenant와 candidate node를 제공합니다.
2. lab은 가장 오래 선택되지 않은 `lastSelectedTick` 순서, 그다음 alias 순서로 tenant를 고릅니다.
3. 선택된 tenant마다 `TenantLockNamespace` 기반 lock name을 만듭니다.
4. active lease는 현재 owner에서만 실행되고 다른 node에서는 skip됩니다.
5. 만료된 lease는 boundary tick에서 handoff됩니다.
6. 실패한 action은 기록하되 한 tenant의 실패가 다른 tenant를 막지 않습니다.
7. report는 event row를 bounded history 안에 보관하고 dropped row를 셉니다.
8. metric tag는 cardinality가 안전할 때만 per-tenant로 유지됩니다.

## Executable Snippet

이 README snippet은 `TenantSchedulerReadmeSnippetTest`가 그대로 실행합니다.

```kotlin
val tenantA = TenantId("tenant-a")
val tenantB = TenantId("tenant-b")
val nodeA = TenantNodeId("node-a")

val policy = TenantSchedulePolicy(
    jobName = TenantJobName("invoice-sync"),
    tenants = listOf(tenantA, tenantB),
    staleAfterTicks = 2,
    maxTenantTagValues = 2,
)

val report = TenantSchedulerLab().run(
    policy = policy,
    ticks = listOf(
        TenantScheduleTick(
            tick = TenantLogicalTick(0),
            candidateNodes = listOf(nodeA),
            actionFailures = listOf(tenantB),
        ),
    ),
)

report.eventRows.map { it.outcome } shouldBeEqualTo listOf(
    TenantRunOutcome.EXECUTED,
    TenantRunOutcome.FAILED,
)

val lockName = TenantLockNamePlanner().lockName(tenantA, policy.jobName)
lockName shouldBeEqualTo "tenant:tenant-a:invoice-sync"

val tags = TenantMetricTagPolicy(maxTenantTagValues = policy.maxTenantTagValues)
    .decide(policy.tenants)

tags.cardinalityLimited.shouldBeFalse()
tags.metricRows.map { it.tags } shouldBeEqualTo listOf(
    mapOf("tenant" to "tenant-a"),
    mapOf("tenant" to "tenant-b"),
)
```

## Scenarios

| Scenario | 학습 포인트 | Local behavior |
|----------|-------------|----------------|
| Independent tenants | 한 tenant의 실패가 다른 tenant 실행을 막으면 안 됩니다. | `tenant-a`가 실패해도 `tenant-b`는 실행됩니다. |
| Stale lease boundary | non-owner는 만료 전에는 skip하고, 만료 tick에서 handoff해야 합니다. | tick `1`은 skip, tick `2`는 `STALE_HANDOFF`를 기록합니다. |
| Fair capacity | bounded tick에서도 뒤쪽 tenant가 굶으면 안 됩니다. | `maxTenantsPerTick=1`일 때 `lastSelectedTick` 기준으로 순환합니다. |
| Bounded history | 큰 tenant set이 무제한 report를 만들면 안 됩니다. | event row는 `eventHistoryLimit`에서 멈추고 dropped row를 셉니다. |
| Metric tags | tenant label은 cardinality가 안전할 때만 유용합니다. | cardinality가 높으면 `tenant=bounded`로 낮춥니다. |

## Run Locally

```bash
./gradlew :leader-tenant-scheduler:test
./gradlew :leader-tenant-scheduler:bootRun
```

기본 테스트 경로는 결정론적이고 인프라가 필요 없습니다. Docker, kubeconfig,
Redis, ZooKeeper, Kubernetes credential 없이 smoke validation에서 안전하게 실행할
수 있습니다.

## Test Map

| Test class | 보호하는 내용 |
|------------|---------------|
| `TenantIdentifierValidationTest` | tenant, job, node alias 정규화, raw input 없는 검증 메시지, account-id 형태 identifier 거부. |
| `TenantLockNamePlannerTest` | `TenantLockNamespace` lock name과 canonicalization 이후 duplicate input 처리. |
| `TenantMetricTagPolicyTest` | per-tenant tag, `tenant=bounded` degradation, local cardinality cap. |
| `TenantSchedulerLabTest` | 독립 tenant 실행, active lease skip, stale handoff, fairness rotation, deterministic report, bounded history. |
| `TenantSchedulerReadmeSnippetTest` | README code path가 실제로 실행되는지 확인합니다. |

## Production Boundaries

이 모듈은 scheduler semantics lab이지 distributed lock backend가 아닙니다.
Production service에서는 tenant-safe name은 유지하되 reducer를
`TenantScopedLeaderElectors`와 선택한 Redis, ZooKeeper, Kubernetes Lease backend로
교체해야 합니다. Production job에는 idempotency, retry policy, graceful shutdown,
alerting, backend health check, lease tuning, 실제 failover exercise가 필요합니다.

tenant alias에는 PII, email address, account ID, raw customer identifier를 넣지
마세요. 안정적인 내부 alias를 사용하고, tag set이 커지면 metric policy가
`tenant=bounded`로 낮추게 둡니다.

## Dependencies

```kotlin
implementation(libs.bluetape4k.core)
implementation(libs.bluetape4k.leader.core)
implementation(libs.bluetape4k.logging)
implementation(libs.spring.boot.autoconfigure.lib)
implementation(libs.spring.boot.starter.actuator)
testImplementation(libs.bluetape4k.assertions)
testImplementation(libs.bluetape4k.junit5)
```
