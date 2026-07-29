# Issue #329 - 테넌트 범위 리더 스케줄러 설계

**날짜**: 2026-07-02
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/329
**마일스톤**: 1.3.1
**상태**: 구현 계획 준비 완료

## 목표

테넌트 범위를 가르치는 `leader/tenant-scheduler` 워크숍 모듈을 추가합니다.
리더 스케줄링.

모듈은 하나의 전역 예약 작업 잠금만으로는 충분하지 않은 이유를 보여줍니다.
다중 테넌트 시스템: 테넌트 A는 테넌트 B가 중단된 경우에도 진행할 수 있어야 합니다.
실패하거나 리더십을 잃거나 오래된 임대가 만료될 때까지 기다립니다. 학습자는 다음을 수행해야 합니다.
잠금 이름, 스케줄러 상태, 공정성, 오래된 잠금 핸드오프 및 메트릭을 확인합니다.
태그는 Redis, ZooKeeper, Kubernetes, PostgreSQL 또는
기본 테스트 경로의 클라우드 계정.

범위 결정: #329은 테넌트 범위 모듈로 구현됩니다. 샤드 범위
스케줄링은 동일한 잠금 이름 패턴을 따르지만 별도의 샤드 추상화를 따릅니다.
샤드 키 형식 및 샤드별 테스트는 이 예시의 범위를 벗어납니다.

## 소스 증거

| 소스 | 증거 |
|--------|----------|
| GitHub 발행 #329 | tenant/shard-based 리더 잠금 이름, 독립적인 예약 작업, 공정성, 오래된 잠금 처리, per-tenant/shard metrics/tags, 결정적 로컬 테스트 및 README 로케일 패리티가 필요합니다. |
| `bluetape4k-leader/leader-core/TenantLockNamespace.kt` | 공식 테넌트 네임스페이스 API는 `prefix:tenantId:lockName`을 반환하고, 공백 값과 `:`를 거부하고, 최종 잠금 이름의 유효성을 검사하고, 주입 네임스페이스 매핑을 유지합니다. |
| `bluetape4k-leader/leader-core/TenantScopedLeaderElectors.kt` | `forTenant(...)` 래퍼는 차단, 코루틴, 그룹 및 가상 스레드 리더 선택기API에 대한 호출자 측 잠금 이름을 변환합니다. |
| `bluetape4k-leader/examples/tenant-aggregator` | 실제 백엔드 중심 테넌트 집계 예제에서는 독립적인 잠금 이름, 장기 실행 코루틴 작업자, 예외 격리 및 정상적인 중지를 사용합니다. Workshop #329은 R2DBC/runtime 동작을 복제하기보다는 보완해야 합니다. |
| `spring-boot/multi-tenant-data-isolation` | 기존 워크샵 모듈은 이미 테넌트 범위의 repository/cache/lock/rate-limit/metrics 키를 로컬 결정적 상태로 가르칩니다. #329는 해당 키 설계 아이디어를 리더 예약에 연결해야 합니다. |
| `leader/backend-comparison-lab` | 최근 리더 워크숍 패턴은 결정론적 로컬 보고서와 소스 기반 README 다이어그램을 사용하여 실제 백엔드 실습 모듈을 교체하지 않고도 운영 리더 행동을 가르칩니다. |
| 저장소-로컬 `AGENTS.md` | 워크숍 모듈은 소비자 프로젝트입니다. 모듈을 추가할 때 루트 `bluetape4k-dependencies` BOM만 사용하고 smoke/workflow/stale-check 등록을 업데이트하세요. |

CodeGraph 참고: 현재 `bluetape4k-workshop` 작업 트리 그래프는 비어 있으므로
repo-local 구조 조회는 직접 소스 읽기로 대체되었습니다. 교차 저장소
CodeGraph는 `TenantLockNamespace`를 `bluetape4k-leader` API로 확인했습니다.

## 브레인스토밍 요약

### 접근 방식 A - 실제 리더 백엔드를 사용하는 전체 런타임 스케줄러

Redis, ZooKeeper 또는 Kubernetes 임대 및
실제 백엔드로 테넌트 독립성을 증명합니다.

**거부됨**: `bluetape4k-leader/examples/tenant-aggregator`이(가) 중복됩니다.
기본 워크샵 테스트 경로를 백엔드 중심으로 만듭니다. Issue #329 명시적으로
로컬 결정론적 테스트가 필요합니다.

### 접근 방식 B - 문서 전용 테넌트 예약 가이드

테넌트 잠금 이름 지정, 공정성 및 공정성을 설명하는 README 페이지와 다이어그램을 추가합니다.
실행 가능한 코드가 없는 메트릭 카디널리티.

**거부됨**: 이는 패턴을 설명하지만 두 가지가 있음을 증명하지는 않습니다.
테넌트가 독립적으로 조정하거나 하나의 테넌트 장애가 차단되지 않음
또 다른.

### 접근 방식 C - 결정적 테넌트 스케줄러 랩

다음을 사용하여 작은 결정론적 모듈을 만듭니다.

- `TenantLockNamespace`을 기반으로 한 테넌트 범위 잠금 이름 계획;
- 불변의 스케줄러 입력 정책;
- 테넌트별 리더 실행, 건너뛰기를 기록하는 로컬 스케줄러 시뮬레이터
  실패, 오래된 임대 핸드오프 및 공정성 보고서;
- 안전한 경계 테넌트 태그를 구별하는 메트릭 태그 지침
  카디널리티가 높은 생산 위험;
- 이중 언어 README 페이지 및 소스 기반 다이어그램.

**선택됨**: 로컬 결정론적 테스트를 유지하면서 #329을 충족합니다.
기존 bluetape4k 리더 API의 재사용을 극대화합니다.

## 설계

### 기준 치수

```text
leader/tenant-scheduler/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/
    TenantSchedulerLabApp.kt
    domain/TenantId.kt
    domain/TenantJobName.kt
    domain/TenantNodeId.kt
    domain/TenantLogicalTick.kt
    domain/TenantLeaseWindow.kt
    domain/TenantSchedulePolicy.kt
    domain/TenantScheduleTick.kt
    domain/TenantLeaseState.kt
    domain/TenantRunOutcome.kt
    domain/TenantSchedulerReport.kt
    service/TenantLockNamePlanner.kt
    service/TenantMetricTagPolicy.kt
    service/TenantSchedulerLab.kt
  src/main/resources/application.yml
  src/test/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/
    service/TenantLockNamePlannerTest.kt
    service/TenantMetricTagPolicyTest.kt
    service/TenantSchedulerLabTest.kt
  src/test/resources/junit-platform.properties
  src/test/resources/logback-test.xml
```

Gradle 프로젝트는 `includeModules("leader", false, true)`에 의해 자동 등록됩니다.
`:leader-tenant-scheduler`으로.

### 핵심 모델

`TenantId`은 호출자 입력의 유효성을 검사하는 작은 직렬화 가능 값 개체입니다.
bluetape4k 검증 도우미. 테스트 및 테스트에서 테넌트 식별자를 읽을 수 있도록 유지합니다.
README 조각.

워크숍에서는 다음과 같은 민감하지 않은 합성 테넌트 별칭만 사용합니다.
`tenant-a` 및 `tenant-b`. `TenantId` 입력을 소문자로 표준화하고
metric/log-safe 별칭만 허용합니다.

- 길이: 3~64자;
- 문법: `[a-z][a-z0-9-]*[a-z0-9]`;
- 거부됨: 공백, `:`, `_`, `.`, `/`, 공백, 제어 문자,
  줄 바꿈, 이메일과 유사한 값, 원시 계정 식별자 및 너무 긴 값
  locks/tags 읽기 가능.

프로덕션 독자는 고객 이름, 이메일, 계정 ID 또는 기타 PII를 매핑해야 합니다.
잠금 이름, 메트릭에 사용하기 전에 안정적이고 민감하지 않은 테넌트 별칭
태그, 로그, 보고서 또는 다이어그램.

`TenantSchedulePolicy`은 다음을 정의합니다.

- `invoice-sync`과 같은 테넌트-로컬 작업 이름;
- 선정된 임차인;
- 틱당 예약 용량, 기본값은 테넌트 수로 지정되므로 모든 기한
  테넌트는 일반 랩 시나리오에서 독립적으로 진행할 수 있습니다.
- 로컬 랩에서 허용되는 최대 메트릭 태그 카디널리티
- 논리적 틱 단위의 오래된 임대 임계값입니다.
- 읽을 수 있는 보고서에 대한 이벤트 기록 제한입니다.

작업 이름은 테넌트 별칭과 동일한 소문자 metric/log-safe 문법을 사용하고
의도적으로 테넌트 로컬입니다. 백엔드 잠금 이름의 출처는 다음과 같습니다.
`TenantLockNamespace(tenantAlias).lockName(jobName)`, 수동 문자열이 아님
연쇄.

빈 테넌트에 대해 `IllegalArgumentException`을 사용하면 정책 유효성 검사가 빠르게 실패합니다.
정규화 후 중복 테넌트, 양수가 아닌 용량, 더 큰 용량
테넌트 수, 양수가 아닌 오래된 임계값, 양수가 아닌 메트릭 태그 제한,
양수가 아닌 보고 기록 제한 또는 위의 보고 기록 제한
`MAX_EVENT_HISTORY_LIMIT`. 과도한 지표 카디널리티 또는 요청된 지표 태그
`MAX_LOCAL_TENANT_TAG_VALUES` 이상의 제한은 구성 실패가 아닙니다. 그것은
`TenantMetricTagPolicy`에 의해 처리되는 보고 가능한 안전 저하 경로.

구현 증거가 표시되지 않는 한 별도의 `fairnessWindow` API를 제거하십시오.
실제 독자가 필요합니다. 선택된 결정론적 공정성 계약은 이미
`lastSelectedTick` 오름차순, 테넌트 별칭.

### API 및 검증 계약

모듈은 동일한 유형의 원시 대신 명명된 불변 값 개체를 사용해야 합니다.
문자열 매개변수:

- 민감하지 않은 테넌트 별칭의 경우 `TenantId`;
- 테넌트-로컬 작업 이름의 경우 `TenantJobName`;
- `TenantNodeId`은 합성 스케줄러 노드 별칭입니다.

도메인 값은 변경할 수 없고 직렬화 가능한 데이터 클래스 또는 값 클래스여야 합니다.
여기서 Kotlin은 필수 검증을 허용합니다. 생성자 또는 컴패니언 팩토리
호출자 입력 및 보존을 위해 bluetape4k 검증 도우미를 사용해야 합니다.
잘못된 호출자 값의 경우 `IllegalArgumentException`입니다. 다음에서 노출된 컬렉션
정책과 보고서는 변경할 수 없는 스냅샷이어야 합니다.

직렬화 가능한 데이터 클래스는 `private const val serialVersionUID: Long`을 정의해야 합니다.
동반 객체를 통해. 데이터 클래스에 유효성 검사가 필요한 경우 비공개를 사용하세요.
생성자와 동반 팩토리 및 `@ConsistentCopyVisibility` 여기서
필요하므로 생성된 `copy`은(는) 검증을 우회할 수 없습니다.

유효성 검사 예외 메시지는 거부된 원시 입력을 반영해서는 안 됩니다. 메시지 이름
필드 및 실패한 규칙만 해당하므로 이메일, 계정 ID와 유사한 값, 제어 문자
샘플 및 기타 안전하지 않은 원시 입력은 로그나 테스트 출력에 표시되지 않습니다.

숫자 정책 값에는 명시적인 제약 조건이 있습니다.

- `1..tenantCount`의 `maxTenantsPerTick`;
- `staleAfterTicks > 0`;
- `maxTenantTagValues > 0`, `TenantMetricTagPolicy` 클램핑 효과 있음
  `MAX_LOCAL_TENANT_TAG_VALUES`으로 출력;
- `1..MAX_EVENT_HISTORY_LIMIT`의 `eventHistoryLimit`.

동일한 유형의 눈금 값에는 명명된 래퍼를 사용합니다. `TenantLogicalTick`은(는)
단일 논리 틱 및 `TenantLeaseWindow(acquiredAt, renewedAt, expiresAt)`
그룹 임대 타임스탬프를 공개 API하여 위치 `Long`/`Int`를 노출하지 않도록 합니다.
`acquiredTick`, `lastRenewedTick`, `expiresAtTick`와 같은 삼중항.

`TenantScheduleTick`은 결정론적 시나리오 입력입니다. 여기에는 논리적인 내용이 포함됩니다.
`tick`, 정렬된 후보 `TenantNodeId` 값, 테스트 시 예정된 테넌트 선택
틱 범위를 좁혀야 함, 테넌트 작업 실패 및 선택적 initial/stale
임대설정. 진드기 및 시나리오 입력 검증을 통해 주문을 결정적으로 만들어야 합니다.
정규화 후 모호한 중복 node/tenant 항목을 거부합니다.
중복 예정 테넌트, 후보 노드, 초기 임대 항목 및
실패 항목.

테스트에서는 다음을 포함하여 `bluetape4k-assertions`을 사용합니다.
`io.bluetape4k.assertions.assertFailsWith`, JUnit을 사용해서는 안 됩니다.
`assertThrows`, AssertJ, Kluent 또는 `kotlin.test` 단언.

`TenantLockNamePlanner`은(는) `TenantLockNamespace(tenant.value).lockName(jobName)`를 사용합니다.
`tenant:tenant-a:invoice-sync`과 같은 백엔드 잠금 이름을 파생합니다. 기획자
또한 학습자가 측정항목 태그 행의 차이를 확인할 수 있도록 표시합니다.
백엔드 잠금 ID 및 제한된 측정항목 차원.

`TenantSchedulerLab`은 분산 잠금을 구현하지 않습니다. 그것은
관찰 가능한 일정 계약:

1. 각 테넌트는 독립적인 논리적 임대 상태를 소유합니다.
2. 각 틱은 테넌트당 후보 노드를 평가합니다.
3. 최대 하나의 노드가 테넌트에 대해 테넌트-로컬 작업을 실행합니다.
4. 한 테넌트의 오류는 실패한 결과를 기록하지만 다른 테넌트를 중지하지는 않습니다.
   같은 진드기의 세입자.
5. 오래된 테넌트 임대는 다른 노드를 변경하지 않고 다른 노드로 전달할 수 있습니다.
   임차인 임대.
6. 기본 용량을 사용하면 모든 예정된 테넌트가 동일한 틱에서 평가됩니다.
   숨겨진 단일 전역 잠금 병목 현상이 없습니다.
7. 테스트에서 의도적으로 용량을 예정된 테넌트 수보다 낮게 설정하는 경우
   연구실은 `lastSelectedTick` 오름차순으로 테넌트를 선택한 다음 테넌트 ID를 선택하므로 기아 상태입니다.
   행동은 결정적이고 테스트 가능합니다.
8. 각 테넌트 내에서 후보 노드는 결정론적 순서로 평가되며
   stale/failing 소유권은 해당 테넌트의 임대 상태에만 영향을 미칩니다.

보고서는 제한이 있고 읽을 수 있는 상태로 유지되어야 합니다. 시나리오 보고서는 이벤트를 유지합니다
README 시나리오를 설명하는 데 필요한 행과 테넌트당 요약된 카운터
그리고 결과. 스트레스 스타일 테스트는 요약 카운터와 제한된
가능한 모든 tenant/node/tick 세부 정보를 영원히 저장하는 대신 기록 제한을 적용합니다.
모든 보고서 행, README 예제, 시퀀스 레이블 및 다이어그램 캡션은
민감하지 않은 테넌트 별칭 정책은 지표와 동일합니다.

보고서 기록은 명시적인 상수로 제한됩니다.

- `DEFAULT_EVENT_HISTORY_LIMIT = 64`;
- `MAX_EVENT_HISTORY_LIMIT = 512`;
- 구성된 제한보다 더 많은 이벤트가 생성되면 보고서는
  첫 번째 설명 행, `truncated=true` 세트 및 증분
  `droppedEventRows`.

### 틱 감속기 및 임대 전환

기본 랩은 유한 순수 논리 틱 감속기입니다.

- 벽시계 없음, 백그라운드 스케줄러, 소유 실행자, `GlobalScope`,
  `Thread.sleep`, 코루틴 `delay`, 기본적으로 Awaitility 또는 폴링 루프
  테스트;
- 시연된 경우 재시도 및 기한은 틱 계산 입력 필드이며
  실제 타이머가 아닌 결과를 보고합니다.
- `TenantSchedulerLab.run(policy, ticks)`은 새로운 불변값을 반환합니다.
  `TenantSchedulerReport` 사이에 정적 변경 가능 상태를 유지하지 않습니다.
  달린다.

각 논리적 틱은 다음 순서로 감소됩니다.

1. policy/tick 입력을 정규화하고 검증합니다.
2. 만기 임차인을 선택하세요. 기본 용량은 모든 예정된 테넌트를 선택합니다. 경계
   용량은 `lastSelectedTick` 오름차순으로 정렬한 다음 테넌트 별칭을 선택하고 다음에서 선택합니다.
   대부분 `maxTenantsPerTick`.
3. 결정적인 순서로 선택된 테넌트를 평가합니다. 실패한 테넌트 작업
   실패한 결과를 기록하지만 나중에 테넌트 평가를 중단하지 않습니다.
   같은 진드기.
4. 입력 순서대로 각 테넌트에 대한 후보 노드를 평가하여 해당 항목만 적용
   임차인의 임대 상태.
5. 리듀서 순서로 제한된 이벤트 행을 추가하고 요약 카운터를 업데이트합니다.

`TenantLeaseState`에는 테넌트 별칭, 잠금 이름, 소유자 노드 별칭이 포함됩니다.
`TenantLeaseWindow`, 그리고 마지막 결과입니다. 임대가 활성 상태인 동안
`currentTick < expiresAtTick`; 다음 경우에 핸드오프가 허용됩니다.
`currentTick >= expiresAtTick`.

임대 전환은 주문되고 테스트 가능합니다.

| 이전 상태 | 후보자 조건 | 성과 | 상태 업데이트 |
|-------------|---------------------|---------|--------------|
| 임대 없음 | 첫 번째 후보가 성공 | `executed` | 소유자 획득, acquired/renewed을 현재 틱으로 설정, 만료를 현재 틱 + 오래된 임계값으로 설정 |
| 임대 없음 | 첫 번째 후보가 실패 | `failed` | 실패한 작업에 대한 소유자 획득, acquired/renewed을 현재 틱으로 설정, 만료를 현재 틱 + 오래된 임계값으로 설정 |
| 활성 임대 | 활성 소유자가 있고 성공합니다 | `executed` | 획득한 틱 유지, 현재 틱으로 갱신 설정, 현재 틱 + 오래된 임계값까지 만료 연장 |
| 활성 임대 | 활성 소유자가 있지만 실패 | `failed` | acquired/renewed/expiry을 변경하지 않고 유지합니다. 갱신하지 마십시오 |
| 활성 임대 | 활성 소유자가 없습니다 | `skipped` | 만료 전 핸드오프 및 상태 변경 없음 |
| 활성 임대 | 비소유자 후보가 만료되기 전에 평가됨 | `skipped` | 상태 변경 없음 |
| 만료된 임대 | 첫 번째 후보가 성공 | `stale-handoff` | 소유자 교체, acquired/renewed을 현재 틱으로 설정, 만료를 현재 틱 + 오래된 임계값으로 설정 |
| 만료된 임대 | 첫 번째 후보가 실패 | `failed` | 실패한 작업의 소유자를 교체하고 acquired/renewed을 현재 틱으로 설정하고 만료를 현재 틱 + 오래된 임계값으로 설정 |

실패한 작업 동작은 명시적입니다. 이미 활성화된 실패한 테넌트 기록입니다.
`failed`은(는) `expiresAtTick`까지 이전 임대를 유지하며 다음 자격을 얻습니다.
동일한 오래된 경계에서만 핸드오프합니다. 다른 테넌트 임대는 수정되지 않습니다.

공정성 상태가 명시적입니다. 선택되지 않은 세입자가 먼저 센티넬로 정렬됩니다.
`lastSelectedTick = TenantLogicalTick.MIN`. 선택한 모든 테넌트가 업데이트됩니다.
`lastSelectedTick` 평가 후, `executed`, `failed`에 관계없이,
`skipped` 또는 `stale-handoff` 결과. 순수하게 선택되지 않은 임차인은 이전의 임차인을 유지합니다.
센티넬 또는 진드기. 제한된 용량 테스트는 반복적으로 회전을 입증해야 합니다.
단일 틱 순서뿐만 아니라 논리적 틱.

결과 차원은 제한이 있고 이름이 지정됩니다: `executed`, `skipped`, `failed`,
`stale-handoff` 및 `deadline-or-retry-exhausted`.

### 종속성 계약 구축

프로덕션 종속성은 루트 BOM을 통해 작고 버전 없이 유지되어야 합니다.

- 허용됨: `bluetape4k-core`, `bluetape4k-leader-core`,
  `bluetape4k-logging`, Spring Boot autoconfigure/actuator 모듈이 유지되는 경우
  근처의 리더 모듈과 동일한 실행 가능한 작업장 형태;
- 허용되는 테스트: `shared`, `bluetape4k-junit5`,
  `bluetape4k-assertions`, Spring Boot 스타터 테스트 및 MockK(테스트인 경우에만)
  정말 모의가 필요합니다.
- 이 모듈의 기본 경로에서는 금지됩니다: Redis, ZooKeeper, Kubernetes,
  PostgreSQL 클라이언트, Testcontainers, Awaitility, 개별 bluetape4k 모듈
  BOM 및 명시적인 bluetape4k 버전.

공개 워크샵에 참여하는 수업 및 기능에는 요약이 포함된 영어 KDoc이 필요합니다.
행동 계약 및 현실적인 조각. 구현 전용 도우미는 다음을 수행해야 합니다.
`internal`이세요.

### 측정항목 지침

`TenantMetricTagPolicy`은 워크숍에서 테넌트별 태그에 대해 정직해야 합니다.
테넌트 세트가 고정되어 있고 작기 때문에 로컬 랩에 테넌트 태그가 표시될 수 있습니다.
그러나 README는 프로덕션 시스템이 테넌트 태그 값을 바인딩해야 함을 경고해야 합니다.
이를 집계하거나 테넌트 카디널리티가 다음인 경우 exemplar/log 상관 관계를 사용합니다.
크기가 큰.

정책 계약은 명시적입니다.

- `DEFAULT_MAX_TENANT_TAG_VALUES = 16`;
- `MAX_LOCAL_TENANT_TAG_VALUES = 100`;
- `tenantCount <= maxTenantTagValues` 및 `maxTenantTagValues`이(가)
  하드 로컬 캡, 메트릭 행에는 `tenant=<tenantAlias>`이 포함될 수 있습니다.
- `tenantCount > maxTenantTagValues` 또는 요청한 제한이 하드 제한을 초과하는 경우
  로컬 한도, 테넌트별 측정항목 행이 억제되고 안전 태그가
  `tenant=bounded`;
- 보고서는 구성된 `cardinalityLimited=true` 경고를 기록합니다.
  한도 및 실제 세입자 수;
- 테스트에서는 안전하지 않은 입력이 구성된 것보다 더 많은 것을 방출하지 않는다는 것을 입증해야 합니다.
  테넌트 태그 바인딩 또는 하드 로컬 제한;
- 측정항목 태그 키가 허용 목록에 추가되었습니다. 백엔드 잠금 이름, 원시 작업을 내보내지 않음
  이름, 노드 ID, 이메일, 계정 ID 모양의 별칭 또는 임의 테넌트 입력
  미터법 차원으로.

### README 콘텐츠 계약

`README.md` 및 `README.ko.md`은 모두 소스와 동일해야 하며 다음을 포함해야 합니다.

- 제목 바로 아래에서 언어를 전환합니다.
- `TenantSchedulePolicy`을 구성하는 테스트된 Kotlin 스니펫이 실행됩니다.
  `TenantSchedulerLab` 논리 틱으로 잠금 검사 names/outcomes/metric
  결정에 태그를 지정하고 예상되는 보고서 모양을 보여줍니다.
- 의미 있는 대체 텍스트 PNG가 포함된 동일한 아키텍처 및 시퀀스 다이어그램
  삽입, `docs/images/readme-diagrams/`에 같은 위치에 있는 SVG 소스 및 짧은
  각 다이어그램 옆에 있는 학습자 테이크아웃;
- 제한된 테넌트 세트를 포함하는 safe/unsafe 메트릭 태그 테이블,
  카디널리티가 높은 테넌트 식별자, `tenant=bounded`,
  aggregation/exemplar 대안 및 실험실에서 구성한 제한
- 명시적인 unsupported/production-boundary 섹션: 배포되지 않음
  조정, 실제 `LeaderElector` 실행 없음, 지속성 없음, 벽시계 없음
  스케줄링, retry/backoff 엔진 없음, 실제 메트릭 내보내기 없음;
- 유한 실험실을 안전하게 reset/rerun하는 방법을 설명하는 실행서 스타일 섹션,
  `cardinalityLimited=true`을 해석하는 방법과 학습자가 해야 할 일
  아이디어를 실제 백엔드에 매핑하기 전 실패했거나 오래된 임대 시나리오
- `TenantScopedLeaderElectors.forTenant(...)`에 대한 마이그레이션 포인터,
  `TenantLockNamespace`, `spring-boot/multi-tenant-data-isolation`,
  `leader/backend-comparison-lab`, `bluetape4k-leader/examples/tenant-aggregator`,
  그리고 실제 Redis/ZooKeeper/Kubernetes 리더 백엔드 모듈입니다.
- `TenantSchedulerReadmeSnippetTest` 또는 동등한 테스트에서 복사된 조각
  고정 장치이므로 README 코드가 컴파일 검증되었습니다.
- 다음과 같이 수정된 토큰으로만 표시되는 안전하지 않은 식별자
  `<email-redacted>` 또는 `acct-<redacted>`.

### 다이어그램

`docs/images/readme-diagrams/` 아래에 두 개의 README 다이어그램을 만듭니다.

1. `leader-tenant-scheduler-readme-architecture-01.svg/png`
   - 정적 소유권 보기: 학습자 입력, 테넌트 잠금 이름 플래너, 스케줄러
     랩, 독립 테넌트 임대 상태, 메트릭 태그 정책 및 실제 리더
     백엔드 연습 경계.
   - "실험실 모델: 분산 잠금 없음"과 같은 눈에 보이는 경계 텍스트를 포함합니다.
     "프로덕션 백엔드: Redis/ZooKeeper/Kubernetes/tenant-aggregator".
   - 소유권 이야기를 단순화하는 경우에만 레이어나 차선을 사용하세요.
   - 커넥터 스타일이 다른 경우 눈에 보이는 범례 또는 인접 README을 포함합니다.
     설명.
2. `leader-tenant-scheduler-readme-sequence-01.svg/png`
   - bluetape4k 모범 사례 시퀀스 스타일을 확립했습니다.
   - 예약된 틱, 테넌트 A 리더십, 테넌트 B 오류 격리를 표시합니다.
     오래된 임대 핸드오프 및 report/metric 방출.
   - 번호가 매겨진 호출 라벨 사용, 투명한 `alt`/`else` 영역 본체, 음소거
     시퀀스 팔레트, 충분한 행 높이 및 선 색상의 화살촉.

다이어그램 작업은 현재 `$bluetape4k-diagram` 체크리스트인 repo-local을 통과해야 합니다.
다이어그램 QA 래퍼, SVG XML 유효성 검사, CairoSVG PNG 렌더링, 전체 크기 PNG
육안 검사, marker/color 감사, 시퀀스 스타일 감사 및 커넥터
해당되는 경우 기하학 감사.

## 논골

- Redis, ZooKeeper, Kubernetes, PostgreSQL, LocalStack 또는 기타 항목을 시작하지 마십시오.
  Testcontainers 서비스는 기본 테스트 중입니다.
- 생산 스케줄러 프레임워크를 만들지 마십시오.
- `bluetape4k-leader/examples/tenant-aggregator` R2DBC 런타임을 복제하지 마세요.
  행동.
- 이 문제에서는 별도의 샤드 추상화 또는 샤드별 테스트를 추가하지 마세요.
- 개별 `bluetape4k-leader` BOM 또는 명시적인 bluetape4k 모듈을 추가하지 마세요.
  버전.
- 백엔드 리더 구현 클라이언트를 추가하지 마세요. Redis/ZooKeeper/Kubernetes
  클라이언트, PostgreSQL 클라이언트, Testcontainers, Awaitility 또는 모듈 로컬 BOM
  기본 모듈로.
- `awaitility`을 사용하지 마십시오. 결정론적 시나리오에서는 직접 상태를 사용해야 합니다.
  검증문.
- 제한되지 않은 테넌트별 측정항목 태그를 프로덕션에 안전한 것처럼 보이게 만들지 마세요.
- PII, 고객 이름, 이메일 또는 원시 계정 ID를 테넌트 ID로 사용하지 마십시오.
  예, 측정항목 태그, 보고서 행, 로그 또는 다이어그램.
- 백그라운드 변경 가능 상태를 숨기거나 랩 간에 lease/report 데이터를 유지하지 마세요.
  달린다.

## 위험 및 완화

| 위험 | 완화 |
|------|------------|
| 이 연구실은 실제 테넌트 리더 API와 다릅니다. | 잠금 이름 파생을 위해 `TenantLockNamespace`을 직접 사용하고 README의 `forTenant(...)` 래퍼에 연결하세요. |
| 학습자는 시뮬레이터를 생산 스케줄러로 착각합니다. | 클래스와 문서의 이름을 랩으로 지정하세요. 실제 백엔드는 `bluetape4k-leader` 실습 모듈에 속한다고 명시합니다. |
| 테넌트 식별자는 잠금 이름, 로그, 지표 또는 다이어그램을 통해 민감한 데이터를 유출합니다. | 합성 metric/log-safe 별칭만 허용하고 프로덕션 별칭 요구 사항을 문서화합니다. |
| 메트릭 카디널리티 지침이 너무 캐주얼합니다. | 명시적인 safe/unsafe 태그 정책 테스트와 README 작동 제한을 추가합니다. |
| Fairness/stale-lock 동작이 비결정적이 됩니다. | 논리적 틱과 불변 입력 시퀀스를 사용하세요. 수면과 벽시계 타이밍을 피하십시오. |
| 하나의 테넌트 오류로 인해 관련 없는 테넌트가 단락됩니다. | 감속기 순서를 정의하고 이전 테넌트가 실패한 후에도 이후 테넌트가 계속 실행되는지 테스트합니다. |
| 보고서 기록은 스트레스 시나리오에서 무제한으로 늘어납니다. | `eventHistoryLimit`, 하드 캡 `MAX_EVENT_HISTORY_LIMIT`, 잘림 필드 및 많은 tenants/nodes/ticks에 대한 결정적 스트레스 테스트를 시행합니다. |
| CI/smoke 검증에서 새 모듈이 생략되었습니다. | 루트 README 로캘 행, `scripts/smoke-validate.sh all-smoke`을 업데이트하고, 예상 개수 `.github/workflows/Examples.yml` paths/jobs/artifacts 오래된 확인하고 `./gradlew projects`를 확인합니다. |
| 다이어그램은 모범 사례 스타일에서 벗어났습니다. | 모범 사례 참조를 먼저 열고 SVG을 PNG로 렌더링하고 모든 터치된 PNG를 전체 크기로 검사하고 구체적인 감사 증거를 기록합니다. |

## 수락 기준

- `leader/tenant-scheduler`이(가) 존재하며 `:leader-tenant-scheduler`로 나열됩니다.
- 빌드는 버전이 없는 `bluetape4k-dependencies` BOM 루트만 사용합니다.
  카탈로그 별칭.
- 기본 테스트는 결정적이며 인프라를 시작하지 않습니다.
- 테스트를 통해 두 테넌트가 독립적으로 조정되는지 확인합니다.
- 테스트는 하나의 테넌트 오류가 다른 테넌트를 차단하지 않는지 확인합니다.
- 테스트를 통해 다른 테넌트를 방해하지 않고 한 테넌트에 대한 오래된 임대 핸드오프를 확인합니다.
  거주자.
- 테스트를 통해 기본 용량을 확인하면 모든 예정된 테넌트가 한 번의 틱으로 진행될 수 있으며
  제한된 용량은 결정적으로 가장 최근에 실행된 테넌트 순서를 사용합니다.
- 테스트를 통해 제한된 용량이 반복되는 논리적 틱에서 공정하게 회전하는지 확인합니다.
  명시적인 `lastSelectedTick` sentinel/update 의미를 사용합니다.
- 테스트에서는 동일한 입력이 실행될 때마다 동일한 보고서를 생성하는지 확인합니다.
- 테스트를 통해 한 테넌트의 오류가 나중에 테넌트를 단락시키지 않는지 확인합니다.
  동일한 틱에서 평가합니다.
- 테스트를 통해 오래된 임대가 `expiresAtTick` 이전에 핸드오프되지 않고 핸드오프되는지 확인합니다.
  `currentTick >= expiresAtTick`인 경우 관련 없는 임차인 임대를 유지합니다.
- 테스트는 실패한 작업이 정의될 때까지 실패한 테넌트 임대를 유지하는지 확인합니다.
  오래된 경계인 경우 동일한 규칙에 따라 핸드오프를 허용합니다.
- 테스트는 동일한 입력을 사용하여 두 개의 랩 인스턴스와 두 개의 연속 실행을 확인합니다.
  lease/report 상태를 공유하지 않습니다.
- 테스트에서는 잠금 이름이 `TenantLockNamespace`을 통해 파생되었으며 유효하지 않은지 확인합니다.
  tenant/job 값은 빠르게 실패합니다.
- 테스트는 테넌트 별칭 및 작업 이름을 확인하고 공백, 콜론, 대문자를 거부합니다.
  표준화 후 드리프트, 제어 문자, 공백, metric/log
  구분 기호, 이메일과 유사한 값, 계정 ID 모양의 값 및 길이 초과
  가치.
- 테스트 검증 예외 메시지에는 거부된 원시 이메일이 포함되지 않습니다.
  계정 ID 모양의 값 또는 제어 문자 입력.
- 테스트를 통해 중복 테넌트, 예정 테넌트, 후보 노드, 초기 임대,
  실패 항목은 정규화 후에 거부됩니다.
- 테스트에서는 `TenantNodeId`, `TenantSchedulePolicy`, `TenantScheduleTick` 및
  제한된 보고서 설정은 유효하지 않거나 모호한 값을 거부합니다.
  `IllegalArgumentException` 및 bluetape4k 어설션 도우미.
- 테스트를 통해 측정항목 태그 정책이 테넌트별 태그를 내보낼 수 있는지 확인합니다.
  `maxTenantTagValues` 및 `MAX_LOCAL_TENANT_TAG_VALUES`, 그렇지 않으면 방출
  `tenant=bounded` 및 카디널리티 경고.
- 테스트를 통해 측정항목 태그가 허용 목록에 있는 키만 사용하고 잠금을 포함하지 않는지 확인합니다.
  이름, 작업 이름, 노드 ID, 이메일, 계정 ID 모양의 별칭 또는 임의의 원시
  임차인 입력.
- 테스트 및 README 예제에서는 중요하지 않은 합성 별칭과 문서만 사용합니다.
  잠금 이름에 PII/customer names/emails/account ID를 넣지 말라고 명시적으로 경고합니다.
  지표 태그, 로그, 보고서 또는 다이어그램.
- 테스트에서는 유효하지 않은 `TenantSchedulePolicy` 값이 빠르게 실패하는지 확인하지만 과도한 값은
  `tenant=bounded` 및 보고서를 통해 테넌트 카디널리티가 안전하게 저하됩니다.
  경고.
- 테스트에는 많은 테넌트에 대한 고정 입력 논리 틱 스트레스 시나리오가 포함됩니다.
  기아가 없음을 검증하는 노드 및 틱, 제한된 보고서 행, 제한된
  메트릭 카디널리티가 있으며 인프라나 절전 모드가 없습니다.
- 테스트는 보고서 잘림 세트 `truncated=true`, 증분을 확인합니다.
  `droppedEventRows`, `eventRows.size <= eventHistoryLimit`을 유지합니다.
- 구현 및 기본 테스트에서는 논리적 틱과 고정 입력만 사용합니다. 아니요
  `Thread.sleep`, 벽시계 폴링, 타이머, 스케줄러 지연 또는 대기.
- `README.md` 및 `README.ko.md`에서는 잠금 이름 지정, 메트릭 카디널리티 위험,
  운영 제한 및 실제 리더 백엔드 모듈과의 관계.
- `README.md` 및 `README.ko.md`은 소스와 동일합니다. 동일한 예, 다이어그램,
  운영 경고, 지원되지 않는 보장 및 마이그레이션 포인터.
- README 런북 지침에서는 랩 reset/rerun, 카디널리티 경고 및
  failed/stale-lease 시나리오 해석.
- README 조각은 컴파일 검증 테스트 코드에서 복사됩니다.
- Public classes/functions은 계약 및 실제 사용이 가능한 영어 KDoc을 보유하고 있습니다.
  짧은 발췌; 구현 전용 도우미는 `internal`입니다.
- README 다이어그램은 SVG+PNG로 존재하며 다이어그램 체크리스트와 시각적 요소를 통과합니다.
  점검.
- 루트 `README.md` 및 `README.ko.md`은 모듈을 나열합니다.
- CI/example 검증에는 연기 범위의 새로운 결정론적 모듈이 포함되며
  경로 필터.

## 확인

- `./gradlew :leader-tenant-scheduler:test --no-build-cache --rerun-tasks`
- `./gradlew :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --warning-mode all`
- `./gradlew projects --console=plain`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-language.mjs`
- `./scripts/smoke-validate.sh stale-check`
- `./scripts/smoke-validate.sh all-smoke`
- `./scripts/smoke-validate.sh diagram-qa`
- `rg -n ":leader-tenant-scheduler:test" scripts/smoke-validate.sh .github/workflows/Examples.yml`
- `if rg -n "testcontainers|awaitility|redis|zookeeper|kubernetes|postgres|bom\\(|version\\(" leader/tenant-scheduler/build.gradle.kts; then exit 1; fi`
- `if rg -n "Thread\\.sleep|delay\\(|GlobalScope|@Scheduled|ScheduledExecutorService|Executors\\.|scheduleAtFixedRate|Timer\\(|System\\.currentTimeMillis|Instant\\.now|Clock\\.system|CoroutineScope\\(|launch\\(|async\\(" leader/tenant-scheduler/src; then exit 1; fi`
- `if rg -n "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\b(?:acct|aws|customer)-[0-9]{6,}\\b" leader/tenant-scheduler/README.md leader/tenant-scheduler/README.ko.md leader/tenant-scheduler/src/main leader/tenant-scheduler/src/test/resources docs/images/readme-diagrams README.md README.ko.md; then exit 1; fi`
- `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml`
- `git diff --check`
