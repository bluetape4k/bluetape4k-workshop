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

## Spring Boot YAML Policy Profile

`scheduled-policy` profile은 plain Spring `@Scheduled` 메서드를
`bluetape4k.leader.scheduling.policies` registry에 연결합니다. 이 profile은
opt-in이므로 결정론적인 reducer가 기본 경로로 유지됩니다.

```bash
./gradlew :leader-tenant-scheduler:test --tests "*TenantScheduledPolicy*"
./gradlew :leader-tenant-scheduler:bootRun --args='--spring.profiles.active=scheduled-policy'
```

profile 기동 시 `Started TenantSchedulerLabAppKt`를 확인하세요. 첫 자동 callback은
최대 60초 뒤에 시작할 수 있습니다. fixture는 `fixedDelay=5s`와
`min-lease-time=5s`를 사용하므로 실제 local period는 최소 약 10초입니다. 각
callback은 bounded smoke 신호로
`tenant-scheduler callback completed invocationCount=...` 로그를 남깁니다. 결정론적
테스트는 `leader.aop.acquire`와 `leader.aop.execution` observation도 기록합니다.
upstream `LeaderElectionAspect`가 runtime Spring proxy로 적용될 수 있도록 fixture는
`open` Spring bean입니다. `spring.aop.auto=false`는 Boot의 두 번째 proxy creator를
막습니다. 이 예제는 외부 backend ownership이나 distributed failover를 증명하지
않습니다.

`@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled`를 명시 annotation으로
사용하는 경로를 검증합니다. 하나라도 있으면 같은 메서드의 property policy보다
annotation이 우선하고, 충돌하는 property는 observed로만 처리되어 적용되지
않습니다. 비어 있거나 malformed, duplicate, unmatched, overload,
invalid-duration인 policy는 startup에서 실패합니다. plain policy도 음수
`wait-time`, 0 이하 `lease-time`, `lease-time`보다 큰 `min-lease-time`을 거부합니다.
이 local lab의 안전한 기본 failure mode는 `SKIP`입니다. `RETHROW`는 job error를
호출자에게 전달하고, `FAIL_OPEN_RUN`은 명시적인 availability 판단을 거친
idempotent 작업에만 사용해야 합니다. task 등록과 취소는 Spring이 소유하며 예제는
executor나 thread를 만들지 않습니다.

```yaml
spring:
  aop:
    auto: false

bluetape4k:
  leader:
    history:
      retention:
        enabled: false
    scheduling:
      enabled: true
      policies:
        - selector: "tenantScheduledPolicyFixture#reconcile"
          name: "tenant-scheduler:reconcile"
          wait-time: 0s
          lease-time: 30s
          min-lease-time: 5s
          bean: "localLeaderElectionFactory"
          auto-extend: false
          stream-bounded: false
          failure-mode: SKIP
    aop:
      strict: true
      spel:
        allow-method-invocation: false
      metrics:
        tags:
          lock-name:
            mode: REDACT
            redacted-value: redacted-lock
    observability:
      tracing:
        enabled: true
        include-lock-name: false
        include-leader-id: false
        include-exception-details: false
```

`history.retention.enabled=false`는 unrelated retention job 대신 이 local
profile의 scheduled policy에 집중하도록 합니다. tracing과 metric 설정은
기본적으로 lock name과 exception detail을 observation에서 숨깁니다. 테스트 전용
`redacted-lock` sentinel로 raw policy name을 노출하지 않는 경계를 확인합니다.

## Rollback / Runbook

1. profile을 끄기 전에 외부 설정의
   `bluetape4k.leader.scheduling.enabled=true` override를 먼저 제거합니다. 실제
   opt-in gate는 profile 이름이 아니라 이 property입니다.
2. `Ctrl-C`로 프로세스를 멈추고 active profile에서 `scheduled-policy`를 제거한
   다음 profile YAML, configuration/fixture, 두 leader dependency alias를 함께
   되돌립니다.
3. 기본 application을 재기동하고 `tenantScheduledPolicyFixture`,
   `LeaderScheduledPolicyRegistry`, policy BPP가 없으며 해당 fixture의 scheduled task도
   `ScheduledTaskHolder`에 남지 않는지 확인합니다. unrelated Spring task는 남을 수
   있습니다. 정상 기동 시에는 여전히 `Started TenantSchedulerLabAppKt`가 출력되어야
   합니다.

callback 로그나 observation 확인이 사라졌을 때 외부 backend를 임시로 켜지 마세요.
exact selector, local factory, profile, proxy 설정을 먼저 원래대로 복구합니다.

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
./gradlew :leader-tenant-scheduler:bootRun --args='--spring.profiles.active=scheduled-policy'
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
| `TenantScheduledPolicyContextTest` | profile binding, exact selector, annotation precedence, fail-fast 검증, proxy observation, opt-out 경계를 확인합니다. |
| `TenantScheduledPolicyLifecycleTest` | Spring task 등록, 즉시 trigger, bounded context-close 동작을 확인합니다. |

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
implementation(libs.bluetape4k.leader.spring.boot)
implementation(libs.bluetape4k.leader.micrometer)
implementation(libs.bluetape4k.logging)
implementation(libs.spring.boot.autoconfigure.lib)
implementation(libs.spring.boot.starter.actuator)
testImplementation(libs.bluetape4k.assertions)
testImplementation(libs.bluetape4k.junit5)
```
