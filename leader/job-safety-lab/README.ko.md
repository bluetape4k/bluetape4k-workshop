# Leader Job Safety Lab

[English](README.md) | 한국어

리더 선출에 성공했다고 해서 작업 결과까지 안전한 것은 아닙니다. 리더의 lease가 끝난 뒤 멈춰 있던 worker가 다시 실행되거나, 두 job 이름이 같은 업무 자원을 건드리거나, tenant/region/version 권한이 실행 중 바뀔 수 있습니다. 이 예제는 그런 상황에서 무엇을 Redis가 막고 무엇을 PostgreSQL이 최종 판단해야 하는지 보여주는 Spring Boot 예제입니다.

핵심 원칙은 간단합니다.

> leader election은 “지금 누가 시도할 수 있는가”를 정하고, fencing은 “이 write가 아직 최신인가”를 PostgreSQL commit 시점에 증명합니다.

이 모듈은 Java 25, Spring Boot 4, `bluetape4k-leader-redis-lettuce`, `bluetape4k-lettuce`, JetBrains Exposed JDBC, `bluetape4k-exposed-jdbc`, PostgreSQL, Redis로 구성합니다. 모든 concrete repository는 `ExposedJdbcRepository`를 구현하며 raw SQL/JDBC escape hatch를 두지 않습니다.

## 한눈에 보는 아키텍처

![Leader job safety architecture](../../docs/images/readme-diagrams/leader-job-safety-lab-architecture-01.png)

안전 경계는 다섯 가지이며 서로 대체할 수 없습니다.

| Guarantee | 이 예제에서의 책임 |
| --- | --- |
| `mutual exclusion` | Redis leader lease와 resource lease가 동시에 실행되는 worker 수를 줄입니다. |
| `failover` | lease 만료 후 다른 worker가 작업을 인계받습니다. 이전 worker의 write 안전성까지 보장하지는 않습니다. |
| `replay safety` | 안정적인 `OperationId`, idempotent provider, receipt unique key가 같은 효과의 재실행을 안전하게 만듭니다. |
| `fencing` | Redis Lua가 단조 증가 token을 발급하고 PostgreSQL이 `incomingFence > lastAcceptedFence`일 때만 write를 받습니다. |
| `durable completion` | business state, checkpoint, execution, outbox가 한 Exposed transaction에 commit되고 외부 효과는 receipt로 완료됩니다. |

리더 backend가 반환하는 owner token은 opaque 값입니다. 순서를 비교하지 않으며 fencing token으로 재사용하지 않습니다. 범용 fencing lease는 [bluetape4k-projects #1068](https://github.com/bluetape4k/bluetape4k-projects/issues/1068)에서 별도로 추적합니다.

## 여섯 가지 실전 시나리오

| Scenario | 흔한 잘못된 가정 | SAFE mode의 방어 |
| --- | --- | --- |
| `CROSS_JOB_COLLISION` | job 이름이 다르면 충돌하지 않는다 | 두 job이 동일한 business `ConflictKey`를 사용하고 resource fence로 직렬화합니다. |
| `LEASE_OVERRUN` | lease를 한 번 얻었으면 작업이 끝날 때까지 안전하다 | B42가 먼저 commit하면 재개된 A41은 `STALE_FENCE`로 거부됩니다. |
| `DYNAMIC_TENANT` | trigger 시점의 tenant 목록이 commit 시점에도 유효하다 | PostgreSQL의 현재 membership revision과 active flag를 transaction 안에서 다시 검사합니다. |
| `REGION_PARTITION` | 각 region의 Redis leader가 있으면 둘 다 write해도 된다 | home region과 region epoch를 PostgreSQL authority와 비교해 non-home write를 거부합니다. |
| `MIXED_VERSION_ROLLOUT` | 오래된 worker도 새 checkpoint에 계속 write할 수 있다 | minimum writer version, checkpoint schema version, namespace epoch를 commit 전에 검사합니다. |
| `NON_FENCEABLE_EFFECT` | fencing token으로 이메일, 결제 같은 외부 효과도 취소할 수 있다 | stable operation ID, transactional outbox, query-before-retry, durable receipt로 복구합니다. |

`UNSAFE` mode는 잘못된 결과를 의도적으로 재현하는 학습용 baseline입니다. production profile에서는 flag와 무관하게 controller bean 자체가 생성되지 않습니다.

## 실행 상태

![Job execution state diagram](../../docs/images/readme-diagrams/leader-job-safety-lab-state-01.png)

상태는 운영 판단에 바로 사용할 수 있도록 서로 다른 의미를 갖습니다.

- `REQUESTED`: trigger가 stable operation ID와 authority snapshot을 만들었습니다.
- `LEADER_ACQUIRED`: 이 worker가 현재 opaque leader lease를 얻었습니다. stale write 권한을 뜻하지 않습니다.
- `FENCE_ACQUIRED`: Redis가 정확한 conflict key에 대해 순서 비교 가능한 resource generation을 발급했습니다.
- `RUNNING`: immutable snapshot과 fence를 사용해 제한된 business work를 실행 중입니다.
- `SKIPPED`: leader 또는 resource fence 경합입니다. 업무 오류가 아니며 다음 trigger에서 다시 시도할 수 있습니다.
- `REJECTED`: stale fence, membership, region, version, namespace처럼 현재 권한과 맞지 않는 요청입니다. 같은 snapshot을 그대로 retry하면 안 됩니다.
- `FAILED`: Redis/backend 또는 domain 실행 실패입니다. 원인을 확인하고 정책에 따라 retry합니다.
- `COMMITTED`: PostgreSQL business state와 outbox가 commit됐습니다. 외부 효과까지 끝났다는 뜻은 아닙니다.
- `EFFECT_PENDING`: commit된 outbox operation이 delivery 또는 confirmation을 기다립니다.
- `RECONCILIATION_REQUIRED`: provider가 적용했는지 알 수 없습니다. 새 operation을 만들지 말고 원래 `OperationId`를 조회합니다.
- `COMPLETED`: provider 결과와 receipt가 durable하게 기록됐습니다.

timeline은 `workshop.job-safety.timeline-limit`으로 제한합니다. API 응답이 무한히 커지지 않으며 잘린 항목 수는 `droppedTimelineEvents`에 표시합니다.

## A41 → B42 takeover

![Fence takeover sequence](../../docs/images/readme-diagrams/leader-job-safety-lab-takeover-sequence-01.png)

Redis lease의 TTL이 끝났다는 사실은 A가 실제로 멈췄다는 뜻이 아닙니다. GC pause, 네트워크 단절, 긴 I/O 뒤에 A가 다시 실행될 수 있습니다.

1. A가 fence 41을 받고 멈춥니다.
2. lease가 만료되고 B가 fence 42를 받습니다.
3. B42가 resource, checkpoint, execution, outbox를 한 transaction에 commit합니다.
4. A41이 재개되지만 PostgreSQL conditional update가 0 row를 변경합니다.
5. A41은 `REJECTED(STALE_FENCE)`가 되고 B42 결과는 유지됩니다.

Redis counter key는 lease key보다 오래 살아야 합니다. counter history를 잃었는데 1부터 다시 발급하면 오래된 큰 token이 새 token보다 커질 수 있습니다. 복구할 때는 다음 중 하나를 사용합니다.

- backup/restore로 counter history를 보존합니다.
- namespace epoch를 올리고 PostgreSQL rollout marker와 함께 새 namespace로 전환합니다.
- epoch mismatch나 counter overflow는 fail closed 처리합니다.

## 실행하기

### 준비물

- JDK 25
- Docker 또는 호환 container runtime
- PostgreSQL 18-compatible server
- Redis 8-compatible server

로컬 backend를 실행합니다.

```bash
docker run --rm --name job-safety-postgres \
  -e POSTGRES_DB=jobsafety -e POSTGRES_USER=jobsafety -e POSTGRES_PASSWORD=jobsafety \
  -p 5432:5432 postgres:18-alpine

docker run --rm --name job-safety-redis -p 6379:6379 redis:8-alpine
```

별도 terminal에서 안전 모드 애플리케이션을 시작합니다.

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/jobsafety \
SPRING_DATASOURCE_USERNAME=jobsafety \
SPRING_DATASOURCE_PASSWORD=jobsafety \
SPRING_SECURITY_USER_NAME=viewer \
SPRING_SECURITY_USER_PASSWORD=change-me \
SPRING_SECURITY_USER_ROLES=JOB_SAFETY_VIEWER,JOB_SAFETY_OPERATOR \
./gradlew :leader-job-safety-lab:bootRun
```

시나리오 목록과 SAFE 결과를 조회합니다.

```bash
curl -u viewer:change-me http://localhost:8080/api/job-safety/scenarios

curl -u viewer:change-me -X POST \
  http://localhost:8080/api/job-safety/scenarios/LEASE_OVERRUN/run
```

operator 작업은 `ROLE_JOB_SAFETY_OPERATOR`가 필요합니다.

```bash
curl -u viewer:change-me -X POST http://localhost:8080/api/job-safety/effects/deliver
curl -u viewer:change-me -X POST http://localhost:8080/api/job-safety/effects/reconcile
curl -u viewer:change-me -X POST http://localhost:8080/api/job-safety/scenarios/LEASE_OVERRUN/reset
```

### 격리된 UNSAFE lab

unsafe endpoint는 `lab-unsafe` profile과 explicit flag가 모두 있어야 합니다. `prod` profile이 함께 있으면 bean은 생성되지 않습니다.

```bash
SPRING_PROFILES_ACTIVE=lab-unsafe \
WORKSHOP_JOB_SAFETY_LAB_UNSAFE_ENABLED=true \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/jobsafety \
SPRING_DATASOURCE_USERNAME=jobsafety \
SPRING_DATASOURCE_PASSWORD=jobsafety \
SPRING_SECURITY_USER_NAME=operator \
SPRING_SECURITY_USER_PASSWORD=change-me \
SPRING_SECURITY_USER_ROLES=JOB_SAFETY_OPERATOR \
./gradlew :leader-job-safety-lab:bootRun

curl -u operator:change-me -X POST \
  http://localhost:8080/api/job-safety/unsafe/scenarios/LEASE_OVERRUN/run
```

이 profile을 shared/production 환경에서 사용하지 마십시오. unsafe 결과는 비교 학습용이며 실제 persistence path를 약화시키는 toggle이 아닙니다.

## PostgreSQL authority와 Exposed transaction

`FencedJobExecutionService`는 다음 순서로 한 `JobSafetyJdbcExecutor.transaction` 안에서 처리합니다.

1. 현재 tenant assignment와 rollout marker를 읽습니다.
2. membership revision, region/epoch, contract version, namespace epoch를 비교합니다.
3. resource row가 현재 namespace이고 incoming fence가 더 클 때만 값을 갱신합니다.
4. checkpoint와 execution을 기록하고 outbox를 enqueue합니다.
5. 하나라도 실패하면 전부 rollback합니다.

repository는 모두 `JobSafetyExposedJdbcRepository`를 통해 Bluetape의 `ExposedJdbcRepository` contract를 구현합니다. `JdbcTemplate`, raw JDBC, `Transaction.exec`를 사용하지 않습니다. 예제 편의를 위해 시작 시 `SchemaUtils.createMissingTablesAndColumns`를 사용하지만, 실서비스에서는 검토 가능한 Flyway/Liquibase migration으로 교체해야 합니다.

Spring context가 종료될 때 `JobSafetyJdbcExecutor`는 Exposed의 process-wide `Database` 등록을 해제합니다. 그렇지 않으면 context restart 뒤 닫힌 Hikari `DataSource`를 다시 참조할 수 있습니다.

## 외부 효과와 reconciliation

DB fencing은 이미 전송된 이메일, 결제 승인, webhook을 되돌릴 수 없습니다. 이 예제는 다음 규칙을 사용합니다.

- business state와 outbox에 동일한 stable `OperationId`를 commit합니다.
- worker는 짧은 transaction에서 row를 claim한 뒤 DB transaction을 닫습니다.
- provider network call은 transaction 밖에서 수행합니다.
- claim 직후 worker가 죽으면 `workshop.job-safety.outbox.claim-timeout` 만료 뒤 원래 operation을 조회합니다. provider를 무조건 다시 실행하지 않습니다.
- `UNKNOWN`이면 새 operation을 만들지 않고 `RECONCILIATION_REQUIRED`를 기록합니다.
- reconciliation은 provider에 원래 operation을 조회하고 receipt를 `(provider, operationId)` unique key로 기록합니다.

provider가 idempotency key나 조회 API를 제공하지 않으면 exactly-once 외부 효과는 보장할 수 없습니다. 수동 확인, 보상 transaction, 업무별 중복 허용 정책이 추가로 필요합니다.

## mixed-version rollout 규칙

권장 순서는 expand → compatible readers → new writers → minimum writer marker 상승 → cleanup입니다.

1. 새 reader가 이전 checkpoint schema도 읽을 수 있게 먼저 배포합니다.
2. checkpoint schema를 올립니다.
3. 충분한 새 writer가 준비된 뒤 PostgreSQL `minimumWriterVersion`을 올립니다.
4. old writer는 `INCOMPATIBLE_VERSION`으로 fail closed합니다.
5. Redis namespace를 바꿔야 하면 PostgreSQL namespace marker와 원자적으로 조정하는 운영 절차를 둡니다.

broker message order, image tag, pod start time은 fencing token이 아닙니다.

## 마이크로서비스로 분리하기

![Microservice extraction guide](../../docs/images/readme-diagrams/leader-job-safety-lab-microservices-01.png)

처음에는 이 예제처럼 한 Spring Boot 애플리케이션 안에서 port와 transaction boundary를 명확하게 만드는 편이 안전합니다. 분리가 필요해지면 다음 ownership을 유지합니다.

- Scheduler service: trigger와 membership snapshot을 만들고 leader election을 수행합니다.
- Execution service: resource fencing, PostgreSQL authority check, checkpoint/execution/outbox atomic commit을 소유합니다.
- Effect worker: outbox claim, provider idempotency, query-before-retry, receipt를 소유합니다.
- Operator control: bounded reconciliation/reset, audit, metric 조회를 소유합니다.

서비스 사이 command에는 `OperationId`, `ConflictKey`, membership revision, region epoch, namespace epoch, contract version을 포함합니다. fencing token은 execution service의 commit 경계까지 전달하되 일반 ordering ID나 broker offset으로 대체하지 않습니다. outbox를 best-effort synchronous HTTP call로 바꾸면 durable completion 보장이 사라집니다.

## Security와 운영 관찰

- stateless JSON API와 HTTP Basic만 사용하므로 CSRF를 비활성화합니다. cookie-backed session을 추가한다면 이 결정을 다시 검토해야 합니다.
- 기본 정책은 `denyAll`; health, authenticated SAFE run, operator-only mutation을 명시적으로 허용합니다.
- 애플리케이션은 사용자를 하드코딩하지 않습니다. production에서는 조직의 identity provider와 credential 정책을 연결합니다.
- unsafe controller는 `lab-unsafe & !prod` profile expression과 explicit property로 이중 차단합니다.
- timeline code와 rejection reason은 low-cardinality입니다. operation ID와 tenant ID를 metric label로 넣지 마십시오.
- Actuator health는 공개할 수 있지만 상세 Redis/PostgreSQL 오류와 credential은 응답에 노출하지 않습니다.

## Observation과 tag 정책

Redis leader와 fencing 실행 경계는 Leader 0.5.0의
`MicrometerObservationLeaderAopMetricsRecorder`와
`MicrometerObservationLeaderElectionListener`에 연결되어 있습니다. coordinator는
leader acquire, fence로 보호되는 실행, release event를 기록하지만 fencing과
failover 판단은 변경하지 않습니다.

기록하는 lifecycle observation은 다음과 같습니다.

- `leader.aop.acquire`: `outcome=acquired|skipped`
- `leader.aop.execution`: `outcome=success|error|cancelled`
- `leader.election.event`: `event=elected|revoked|skipped`

기본 `LeaderObservationOptions`는 `lock.name`과 `leader.id`를 12자리 lowercase
SHA-256 prefix로 hash하고 exception detail은 비활성화합니다.
`JobRunCoordinator`는 job name으로 lock name을 만들지만 sanitizer 입력으로만
사용합니다. raw job, tenant, operation, fencing-owner 식별자는 observation tag로
내보내지 않습니다. hash는 raw 값을 숨기지만 서로 다른 값의 개수까지 줄이지
않으므로 series 개수를 엄격하게 제한해야 하면 `REDACT` 또는 명시적인
allowlist를 사용합니다.

```kotlin
@Bean
fun leaderObservationOptions() = LeaderObservationOptions(
    includeLockName = true,
    includeLeaderId = true,
    tagOptions = LeaderMetricTagOptions(
        lockName = LeaderMetricTagRule(mode = LeaderMetricTagMode.HASH, hashLength = 12),
        leaderId = LeaderMetricTagRule(mode = LeaderMetricTagMode.REDACT),
    ),
)
```

`bluetape4k-leader-spring-boot`를 포함한 애플리케이션은 이 lab의 수동
observation bean을 제거하거나 override한 뒤 observation auto-configuration을
사용할 수 있습니다. 이 lab은 Redis leader와 PostgreSQL fencing 경계를 명확히
보여주기 위해 recorder와 listener를 수동으로 연결합니다.

## 테스트

```bash
# container를 시작하지 않는 빠른 경로
./gradlew :leader-job-safety-lab:test

# 실제 PostgreSQL + Redis, 직렬 실행
./gradlew :leader-job-safety-lab:integrationTest --max-workers=1
```

| Proof | Test |
| --- | --- |
| Leader 0.5.0 lifecycle observation과 sanitized identifier | `JobRunCoordinatorTest` |
| Java 25 virtual thread와 안전 기본값 | `JobSafetyRuntimeContractTest`, `JobSafetyPropertiesTest` |
| opaque leader token 분리 | `RedisLeaderElectionAdapterTest` |
| Lua token 단조 증가, renew/release owner binding, script flush | `RedisJobFencingLeaseIntegrationTest` |
| fence 42 commit 후 fence 41 reject | `FencedMutationPostgresIntegrationTest`, `JobSafetyEndToEndIntegrationTest` |
| tenant/region/version/namespace authority | `JobAuthorityPostgresIntegrationTest` |
| transaction 밖 provider call과 restart recovery | `OutboxEffectWorkerTest`, `JobSafetyContextRestartIntegrationTest` |
| raw DB access 금지와 Exposed repository contract | `KotlinPatternArchitectureTest`, `JobSafetyRepositoryContractTest` |
| unsafe 이중 gate와 operator 권한 | `UnsafeJobSafetyControllerConditionTest`, `JobSafetySecurityTest` |

## 한계와 다음 단계

- API의 scenario snapshot은 deterministic 교육 모델입니다. backend integration tests가 실제 Redis/PostgreSQL safety contract를 증명합니다.
- `DeterministicExternalEffectAdapter`는 provider 장애를 재현하는 fake입니다. 실제 provider adapter에는 timeout, idempotency, lookup, rate limit 정책이 필요합니다.
- 이 모듈은 single Redis deployment를 사용합니다. multi-region에서 하나의 global fencing history가 필요하면 Redis topology와 PostgreSQL write-home 설계를 함께 검증해야 합니다.
- 예제의 `SchemaUtils`는 개발용입니다. production migration, backup, namespace epoch 변경 runbook은 별도로 운영해야 합니다.
- in-memory scenario reset은 상태를 지울 필요가 없습니다. 실제 운영 reset endpoint는 승인, 감사, scope 제한, dry-run을 추가해야 합니다.

관련 자료:

- [Tenant Scheduler example](../tenant-scheduler/README.md)
- [Leader Backend Comparison Lab](../backend-comparison-lab/README.md)
- [Leader election article PR #249](https://github.com/bluetape4k/bluetape4k.github.io/pull/249)
- [Workshop issue #548](https://github.com/bluetape4k/bluetape4k-workshop/issues/548)
- [Reusable fencing lease issue #1068](https://github.com/bluetape4k/bluetape4k-projects/issues/1068)
