# 테넌트 범위 리더 스케줄러 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> 초능력:하위 에이전트 중심 개발 또는 초능력:계획 실행
> 이 계획을 작업별로 실행하세요. 각 확인란을 최신 상태로 유지하십시오.

**목표:** 결정론적 워크숍 모듈인 `leader/tenant-scheduler`을 구축합니다.
다음에서 파생된 잠금 이름을 사용하여 테넌트 범위 리더 일정 관리를 가르칩니다.
`TenantLockNamespace`.

**아키텍처:** 모듈은 유한 논리 틱 랩입니다. 세입자-로컬을 모델링합니다.
임대, 테넌트별 execution/failure/stale-handoff 결과 및 제한된 측정항목
시작하지 않고 태그 안내 Redis, ZooKeeper, Kubernetes, PostgreSQL,
Testcontainers, 백그라운드 스케줄러, 실시간 타이머, Awaitility 또는 폴링 루프.

**기술 스택:** Kotlin plugin/catalog 2.4.0(루트 `languageVersion` 포함) 및
`apiVersion` Kotlin 2.3으로 설정, Java 21, Spring Boot 4 모듈 모양, 루트
`bluetape4k-dependencies` BOM만, `bluetape4k-core`, `bluetape4k-leader-core`,
`bluetape4k-logging`, JUnit 5, `bluetape4k-assertions`, 이중 언어 README 페이지,
생성된 SVG+PNG README 다이어그램, GitHub 작업 연기 검증.

이 모듈에서는 Kotlin 2.4 전용 언어나 API 기능을 사용하지 마세요.

**사양:** `docs/superpowers/specs/2026-07-02-issue-329-tenant-scheduler-design.md`

## 파일 구조

- `leader/tenant-scheduler/build.gradle.kts`를 생성합니다.
- `leader/tenant-scheduler/README.md`를 생성합니다.
- `leader/tenant-scheduler/README.ko.md`를 생성합니다.
- `leader/tenant-scheduler/src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/TenantSchedulerLabApp.kt`를 생성합니다.
- 도메인 파일 만들기:
  - `domain/TenantId.kt`
  - `domain/TenantJobName.kt`
  - `domain/TenantNodeId.kt`
  - `domain/TenantLogicalTick.kt`
  - `domain/TenantLeaseWindow.kt`
  - `domain/TenantSchedulePolicy.kt`
  - `domain/TenantScheduleTick.kt`
  - `domain/TenantLeaseState.kt`
  - `domain/TenantRunOutcome.kt`
  - `domain/TenantSchedulerReport.kt`
- 서비스 파일을 생성합니다:
  - `service/TenantLockNamePlanner.kt`
  - `service/TenantMetricTagPolicy.kt`
  - `service/TenantSchedulerLab.kt`
- 테스트 만들기:
  - `service/TenantIdentifierValidationTest.kt`
  - `service/TenantLockNamePlannerTest.kt`
  - `service/TenantMetricTagPolicyTest.kt`
  - `service/TenantSchedulerLabTest.kt`
- `src/main/resources/application.yml`를 생성합니다.
- `src/test/resources/junit-platform.properties`를 생성합니다.
- `src/test/resources/logback-test.xml`를 생성합니다.
- 다이어그램 만들기:
  - `docs/images/readme-diagrams/leader-tenant-scheduler-readme-architecture-01.svg`
  - `docs/images/readme-diagrams/leader-tenant-scheduler-readme-architecture-01.png`
  - `docs/images/readme-diagrams/leader-tenant-scheduler-readme-sequence-01.svg`
  - `docs/images/readme-diagrams/leader-tenant-scheduler-readme-sequence-01.png`
- 루트 `README.md` 및 `README.ko.md`을 수정합니다.
- `scripts/smoke-validate.sh`을 수정하세요.
- `.github/workflows/Examples.yml`을 수정하세요.
- 워크플로 검색에 새 항목이 표시되는 경우에만 `.github/workflows/nightly.yml`을 수정하세요.
  모듈은 기존의 전체 연기 경로에 포함되지 않습니다.
- `docs/review/2026-07-02-issue-329-tenant-scheduler-code-review.md`를 생성합니다.
- `docs/lessons/2026-07-02-issue-329-tenant-scheduler.md`를 생성합니다.

## 작업 0: 검토한 사양 및 계획 커밋

**복잡성:** 낮음

**적용:** `$bluetape4k-full-feature`

- [ ] 계획 초안 표시 및 일관성 확인을 실행합니다.
- [ ] 3-R단계 계획 검토를 실행하고 P0/P1 결과를 ​​적용합니다.
- [ ] 구현이 시작되기 전에 검토된 사양과 계획을 커밋합니다.

확인:

```bash
rg -n "TB[D]|TO[D]O|FIXM[E]|\\?\\?|mayb[e]" \
  docs/superpowers/specs/2026-07-02-issue-329-tenant-scheduler-design.md \
  docs/superpowers/plans/2026-07-02-issue-329-tenant-scheduler-plan.md
git diff --check
```

## 작업 1: 뼈대 구축 및 RED 검증 테스트

**복잡성:** 중간

**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] 버전 없는 `leader/tenant-scheduler/build.gradle.kts` 추가
  종속성만.
- [ ] 근처의 리더 Spring Boot 모듈 모양을 미러링합니다.
  `kotlin.spring`, `spring.boot`, `springBoot.mainClass`, 주석 프로세서,
  devtools 런타임 및 JUnit 빈티지에 대한 Spring Boot 스타터 테스트 제외 및
  모키토.
- [ ] JUnit 병렬 실행이 비활성화된 테스트 리소스를 추가하고
  클래스별 수명주기.
- [ ] `TenantId`, `TenantJobName` 및 `TenantNodeId`에 대한 RED 테스트를 추가합니다.
- [ ] 생산 유형이 아직 존재하지 않기 때문에 테스트가 실패했음을 증명하십시오.

구현 제약:

- 루트 BOM 및 카탈로그 별칭만 사용하세요.
- 생산 종속성은 결정론적 실험실 요구 사항으로 제한됩니다.
  `bluetape4k-core`, `bluetape4k-leader-core`, `bluetape4k-logging`, Spring Boot
  실행 가능한 작업장 형태의 경우 autoconfigure/actuator.
- 종속성 테스트: `project(":shared")`, `bluetape4k-junit5`,
  `bluetape4k-assertions`, Spring Boot 스타터 테스트.
- Awaitility, Testcontainers, Redis, ZooKeeper, Kubernetes, 데이터베이스를 추가하지 마세요.
  또는 백엔드 클라이언트 종속성.

RED 명령:

```bash
./gradlew :leader-tenant-scheduler:test \
  --tests '*TenantIdentifierValidationTest' \
  --no-build-cache --rerun-tasks
```

예상: FAIL 왜냐하면 식별자 값 개체가 존재하지 않기 때문입니다.

## 작업 2: 식별자 값 객체 구현

**복잡성:** 중간

**적용:** `$bluetape4k-code-patterns`

- [ ] `TenantId`, `TenantJobName`, `TenantNodeId`를 불변으로 구현하세요.
  직렬화 가능한 도메인 값.
- [ ] 개인 생성자와 동반자 `operator fun invoke(...)` 사용
  공장; 표준 소문자 `value`을 노출합니다.
- [ ] 데이터 클래스에 비공개가 필요한 곳에 `@ConsistentCopyVisibility`을 추가합니다.
  생성자.
- [ ] 모든 직렬화 가능한 데이터 클래스에 `serialVersionUID`을 추가합니다.
- [ ] 공개 값 유형 및 팩토리에 영어 KDoc을 추가합니다.
- [ ] 허용된 입력을 소문자로 정규화합니다.
- [ ] 길이가 `3..64`인 `[a-z][a-z0-9-]*[a-z0-9]`만 허용됩니다.
- [ ] 공백, `:`, `_`, `.`, `/`, 공백, 제어 문자를 거부합니다.
  줄 바꿈, 이메일과 유사한 값, 원시 계정 식별자 및 길이를 초과하는 값.
- [ ] 다음과 같은 패턴을 포함하여 계정 ID 모양의 별칭을 거부합니다.
  `acct-123456789012`, `aws-123456789012`, `customer-123456`.
- [ ] 어설션 유효성 검사 예외 메시지에는 거부된 원시 입력이 포함되어 있지 않습니다.
  메시지에는 필드와 실패한 규칙만 이름이 지정됩니다.
- [ ] bluetape4k 검증 도우미를 사용하고 `IllegalArgumentException` 보존
  발신자 입력 실패의 경우.

확인:

```bash
./gradlew :leader-tenant-scheduler:test \
  --tests '*TenantIdentifierValidationTest' \
  --no-build-cache --rerun-tasks
```

## 작업 3: Lock Planner 및 메트릭 태그 정책 구현

**복잡성:** 중간

**적용:** `$bluetape4k-code-patterns`

- [ ] `TenantLockNamePlanner`에 대한 RED 테스트를 추가합니다.
- [ ] `TenantMetricTagPolicy`에 대한 RED 테스트를 추가합니다.
- [ ] 집중 테스트를 실행하고 생산 전에 예상되는 RED 실패를 기록하세요.
  구현.
- [ ] 다음을 사용하여 `TenantLockNamePlanner`을 구현합니다.
  `TenantLockNamespace(tenant.value).lockName(jobName.value)`.
- [ ] 생성된 백엔드 잠금 이름이 다음과 같다는 것을 증명하세요.
  `tenant:tenant-a:invoice-sync`.
- [ ] 백엔드 잠금 ID를 구별하는 학습자에게 표시되는 행을 노출합니다.
  미터법 차원.
- [ ] 안전한 측정항목 태그 지침을 구현합니다.
  - `DEFAULT_MAX_TENANT_TAG_VALUES = 16`;
  - `MAX_LOCAL_TENANT_TAG_VALUES = 100`;
  - `tenantCount <= maxTenantTagValues`이고 요청된 제한이
    하드 로컬 제한, 테넌트별 태그 내보내기
  - `tenantCount > maxTenantTagValues` 또는 요청한 제한이 하드 제한을 초과하는 경우
    로컬 캡, `tenant=bounded` 및 `cardinalityLimited=true` 방출;
  - 원시 PII, 백엔드 잠금 이름, 작업 이름, 노드 ID 또는 무제한을 포함하지 마십시오.
    테넌트 값을 측정항목 차원으로 표시합니다.
- [ ] 예를 들어 `tenantCount=10_000`과 같은 높은 카디널리티 테스트를 추가하여
  호출자가 큰 태그 제한을 요청하는 경우에도 방출된 지표 행은 제한된 상태로 유지됩니다.
- [ ] `lockName`, 백엔드 잠금 문자열, 원시 작업을 금지하는 허용 태그 테스트를 추가합니다.
  이름 및 노드 ID를 측정항목 차원으로 사용합니다.

확인:

```bash
./gradlew :leader-tenant-scheduler:test \
  --tests '*TenantLockNamePlannerTest' \
  --tests '*TenantMetricTagPolicyTest' \
  --no-build-cache --rerun-tasks
```

## 작업 4: 결정적 스케줄러 실습 구현

**복잡성:** 높음

**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] 정책 유효성 검사, 틱 유효성 검사 및 결정론적 테스트를 위한 RED 테스트를 추가합니다.
  보고서.
- [ ] 집중 테스트를 실행하고 생산 전에 예상되는 RED 실패를 기록하세요.
  구현.
- [ ] 정책, 틱, 임대 상태에 대해 변경할 수 없는 도메인 값을 구현합니다.
  결과, 이벤트 행 및 보고서.
- [ ] `TenantLogicalTick`를 소개하고
  `TenantLeaseWindow(acquiredAt, renewedAt, expiresAt)` 공개 생성자는 그렇게 합니다.
  동일한 유형의 틱 매개변수를 여러 개 노출하지 마세요.
- [ ] 모든 직렬화 가능한 데이터 클래스에 `serialVersionUID`을 추가합니다.
- [ ] `TenantSchedulerLab.run(policy, ticks)`을 순수 리듀서로 구현하세요.
- [ ] 독립적인 테넌트 상태 유지: 테넌트 오류는 나중에 중단되지 않습니다.
  동일한 틱에서 테넌트 평가.
- [ ] 임대 의미를 보존합니다.
  - `currentTick < expiresAtTick` 동안 활성화됨;
  - `currentTick >= expiresAtTick`일 때 오래된 핸드오프가 허용됩니다.
  - 실패한 임차인 조치는 동일한 오래된 경계까지 임대를 유지합니다.
  - 다른 테넌트 임대는 수정되지 않습니다.
- [ ] 정확한 임대 전환 행을 구현합니다.
  - 임대가 없으며 성공적인 첫 번째 후보가 인수 및 실행됩니다.
  - 임대 없음 및 실패한 첫 번째 후보 획득, 기록 `failed` 및 유지
    새로운 만료;
  - 활성 소유자가 존재하고 성공하면 만료가 갱신되고 연장됩니다.
  - 활성 소유자가 존재하고 오류가 발생하면 acquired/renewed/expiry이 변경되지 않고 유지됩니다.
  - 만료 전에 부재 중인 활성 소유자는 `skipped`을 기록하고 양도하지 않습니다.
  - 만료 전 소유자가 아닌 후보는 `skipped`을 기록하고 상태를 변경하지 않습니다.
  - 만료된 임대와 성공적인 첫 번째 후보 기록 `stale-handoff`;
  - 만료된 임대와 실패한 첫 번째 후보가 실패한 임대의 소유자를 대체합니다.
    조치를 취하고 새로운 만료를 유지합니다.
- [ ] `lastSelectedTick` 오름차순으로 제한된 용량 공정성을 구현한 다음
  테넌트 별칭.
- [ ] `TenantLogicalTick.MIN`을 사용하여 선택되지 않은 테넌트를 초기화합니다.
- [ ] 다음을 포함하여 선택한 모든 테넌트 결과에 대해 `lastSelectedTick`을 업데이트합니다.
  `executed`, `failed`, `skipped`, `stale-handoff`; 업데이트하지 않음 선택 취소됨
  세입자.
- [ ] 보고서 행을 `DEFAULT_EVENT_HISTORY_LIMIT = 64`으로 묶어 유지하고
  `MAX_EVENT_HISTORY_LIMIT = 512`.
- [ ] 행이 구성된 제한을 초과하면 `truncated=true`을 설정하고 증분합니다.
  `droppedEventRows`, `eventRows.size <= eventHistoryLimit`을 유지합니다.
- [ ] 반복되는 호출과 별도의 랩 인스턴스가 변경 가능한 항목을 공유하지 않는지 확인합니다.
  상태.

필수 테스트:

- [ ] 두 테넌트가 한 틱에 독립적으로 실행됩니다.
- [ ] 한 테넌트 오류는 다른 테넌트를 차단하지 않습니다.
- [ ] 오래된 임대는 만료되기 전에 양도할 수 없습니다.
- [ ] 오래된 임대는 만료 경계에서 정확히 중단됩니다.
- [ ] 실패한 작업은 만료될 때까지 테넌트 임대를 유지합니다.
- [ ] 제한된 용량은 가장 최근에 실행된 테넌트를 결정적으로 선택합니다.
- [ ] 최소 3개 테넌트에 걸쳐 용량=1은 반복되는 틱에 걸쳐 순환하며
  선택된 sentinel/update 규칙에 따라 기아 상태가 없음을 증명합니다.
- [ ] 동일한 입력으로 동일한 보고서가 생성됩니다.
- [ ] 별도의 실행으로 인해 상태가 누출되지 않습니다.
- [ ] 스트레스 스타일의 논리적 틱 시나리오는 제한적이고 결정적입니다.
- [ ] 스트레스 스타일 시나리오는 `eventRows.size <= eventHistoryLimit`을 검증문합니다.
  잘림 필드, 제한된 메트릭 행, 많은 테넌트에 대한 기아 없음,
  노드 및 틱.
- [ ] 정규화 후 중복 테스트는 중복 테넌트를 포함합니다.
  테넌트, 후보 노드, 초기 임대 및 실패 항목.
- [ ] 소스 스캔에서는 Awaitility, `Thread.sleep`, 코루틴 `delay`이 없음을 확인합니다.
  `GlobalScope`, 숨겨진 scheduler/clock API, 폴링 루프, Testcontainers 또는
  백엔드 클라이언트 사용.

확인:

```bash
./gradlew :leader-tenant-scheduler:test --no-build-cache --rerun-tasks
if rg -n "awaitility|Awaitility|Thread\\.sleep|delay\\(|GlobalScope|@Scheduled|ScheduledExecutorService|Executors\\.|scheduleAtFixedRate|Timer\\(|System\\.currentTimeMillis|Instant\\.now|Clock\\.system|CoroutineScope\\(|launch\\(|async\\(|Testcontainers|GenericContainer|Redis|ZooKeeper|Kubernetes|PostgreSQL" \
  leader/tenant-scheduler/src; then exit 1; fi
if rg -n "testcontainers|awaitility|redis|zookeeper|kubernetes|postgres|bom\\(|version\\(" \
  leader/tenant-scheduler/build.gradle.kts; then exit 1; fi
```

금지된 스캔은 금지된 runtime/test/build 사용법을 반환하지 않아야 합니다.

## 작업 5: 학습자 README 페이지 추가

**복잡성:** 중간

**적용:** `$bluetape4k-blog`, `$bluetape4k-diagram`

- [ ] 영어`leader/tenant-scheduler/README.md`를 추가합니다.
- [ ] 소스에 해당하는 한국어 `leader/tenant-scheduler/README.ko.md`를 추가합니다.
- [ ] 각 제목 바로 아래에 언어 스위치를 추가합니다.
- [ ] 하나의 전역 예약 작업 잠금이 테넌트 진행을 차단하는 이유를 설명하세요.
- [ ] `TenantLockNamespace`으로 잠금 이름 지정을 설명하고 생성된 결과를 표시합니다.
  `tenant:tenant-a:invoice-sync` 형식입니다.
- [ ] safe/unsafe 예시를 통해 측정항목 카디널리티 제한을 설명하세요.
- [ ] 운영 제한 설명: 오래된 임대, retry/deadline 의미,
  실패 격리, tags/logs/locks에 PII 없음, 실제로 이동할 시기
  백엔드 모듈.
- [ ] 다음에서 복사한 테스트된 Kotlin 조각을 포함합니다.
  `TenantSchedulerReadmeSnippetTest` 또는 이에 상응하는 테스트 픽스처.
- [ ] reset/rerun에 대한 런북 섹션 추가, 카디널리티 경고 해석,
  실패한 작업 해석, 오래된 임대 해석 및 다음 작업
  실제 백엔드에 매핑하기 전에.
- [ ] 다음과 같이 안전하지 않은 예에만 수정된 토큰을 사용하세요.
  `<email-redacted>` 및 `acct-<redacted>`.
- [ ] 실제 연습 경로 링크:
  - `leader/leader-election`
  - `leader/leader-zookeeper`
  - `leader/k8s-lease-micrometer`
  - `leader/backend-comparison-lab`
  - `spring-boot/multi-tenant-data-isolation`
  - `bluetape4k-leader/examples/tenant-aggregator`

확인:

```bash
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
./gradlew :leader-tenant-scheduler:test --tests '*TenantSchedulerReadmeSnippetTest'
if rg -n "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\b(?:acct|aws|customer)-[0-9]{6,}\\b" \
  leader/tenant-scheduler/README.md leader/tenant-scheduler/README.ko.md \
  leader/tenant-scheduler/src docs/images/readme-diagrams; then exit 1; fi
```

## 작업 6: README 다이어그램 생성 및 감사

**복잡성:** 높음

**적용:** `$bluetape4k-diagram`

- [ ] 모범 사례 아키텍처 및 시퀀스 참조를 전체 크기로 검사
  그리기 전에.
- [ ] 레이어 경계가 명확하고 단순하여 아키텍처 다이어그램을 생성합니다.
  위에서 아래로의 흐름.
- [ ] 눈에 보이는 생산 경계를 포함합니다(예: '실험실 모델: 아니요)
  분산 잠금' 대 '프로덕션 백엔드:
  Redis/ZooKeeper/Kubernetes/tenant-aggregator".
- [ ] 모범 사례 스타일로 시퀀스 다이어그램을 만듭니다.
  - 번호가 매겨진 통화 라벨;
  - 라벨은 통화 회선을 덮지 않습니다.
  - 화살촉 색상은 호출 라인 색상과 일치합니다.
  - alt/else 영역은 투명합니다.
  - 둥근 모서리 직교 커넥터;
  - 중앙 카드 텍스트;
  - 음소거된 모범 사례 팔레트.
- [ ] 동일한 소스에서 SVG 및 PNG을 내보냅니다.
- [ ] 전체 다이어그램 체크리스트를 실행하세요.
- [ ] 전체 크기로 렌더링된 PNG에 대해 육안 검사를 수행합니다.
- [ ] 다이어그램을 삽입하기 전에 모든 체크리스트나 시각적 결과를 수정하세요.

확인:

```bash
./scripts/smoke-validate.sh diagram-qa
```

필요한 시각적 증거:

- 전체 크기 PNG 검사 결과 icons/images가 파손되지 않았음을 확인했습니다.
- SVG 및 PNG 화살표 방향이 일치합니다.
- 커넥터는 짧고 직각이며 구부러진 부분이 둥글고 텍스트를 가리지 않습니다.
- 카드는 일관된 텍스트 정렬과 레이어 스타일을 사용합니다.

## 작업 7: Repo 및 CI에 모듈 등록

**복잡성:** 중간

**적용:** `$bluetape4k-code-patterns`

- [ ] `settings.gradle.kts` 자동 등록 확인
  `:leader-tenant-scheduler`.
- [ ] 루트 `README.md` 및 `README.ko.md`에 새 모듈을 추가합니다.
- [ ] `scripts/smoke-validate.sh` 예상 모듈 수 및 전체 연기 업데이트
  테스트 목록.
- [ ] `.github/workflows/Examples.yml` 경로 필터 업데이트, 연기 테스트 명령,
  및 테스트 결과 아티팩트.
- [ ] `.github/workflows/nightly.yml`를 스캔하고 새 모듈이 있는 경우에만 업데이트하십시오.
  전체 연기로 덮여 있지 않습니다. 야간에 `all-smoke`을 호출하는 경우 이를 기록하십시오.
  모듈별 야간 편집은 필요하지 않습니다.
- [ ] 프로젝트 목록 실행, smoke 명령 존재, all-smoke 및 stale 실행
  등록 확인.
- [ ] 프로젝트 수 또는 등록 드리프트에 대한 오래된 확인 경고를 다음과 같이 처리합니다.
  PR 이전에 수리 실패.

확인:

```bash
./gradlew projects --console=plain
rg -n ":leader-tenant-scheduler:test" scripts/smoke-validate.sh .github/workflows/Examples.yml
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml
```

## 작업 8: 최종 확인, 검토 및 PR

**복잡성:** 높음

**적용:** `$bluetape4k-workflow`, `$bluetape4k-full-feature`,
`$bluetape4k-code-patterns`, `$bluetape4k-diagram`

- [ ] 대상 모듈 테스트를 실행합니다.
- [ ] 경고 모드로 컴파일 검사를 실행합니다.
- [ ] README 스니펫 테스트를 실행하세요.
- [ ] README parity/language 검사를 실행합니다.
- [ ] 다이어그램 QA을 실행하고 육안 검사를 수행합니다.
- [ ] smoke 명령 존재, all-smoke 및 오래된 등록 확인을 실행합니다.
- [ ] 변경된 위치에서 워크플로 린트를 실행합니다.
- [ ] 금지된 dependency/runtime/PII 검사를 실행합니다.
- [ ] `git diff --check`를 실행하세요.
- [ ] `docs/review/2026-07-02-issue-329-tenant-scheduler-code-review.md` 생성
  7계층 검토 증거를 사용합니다.
- [ ] `docs/lessons/2026-07-02-issue-329-tenant-scheduler.md`를 생성합니다.
- [ ] Lore 예고편으로 커밋하세요.
- [ ] 이슈 #329에 대한 PR을 열고 마일스톤, 라벨 및 담당자를 복사합니다.
- [ ] 실시간 PR 본문, 마일스톤, 라벨, 담당자, 수표 및 이슈 링크를 확인합니다.

확인 명령 세트:

```bash
./gradlew :leader-tenant-scheduler:test --no-build-cache --rerun-tasks
./gradlew :leader-tenant-scheduler:test --tests '*TenantSchedulerReadmeSnippetTest'
./gradlew :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --warning-mode all
./gradlew projects --console=plain
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
rg -n ":leader-tenant-scheduler:test" scripts/smoke-validate.sh .github/workflows/Examples.yml
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh diagram-qa
if rg -n "testcontainers|awaitility|redis|zookeeper|kubernetes|postgres|bom\\(|version\\(" leader/tenant-scheduler/build.gradle.kts; then exit 1; fi
if rg -n "Thread\\.sleep|delay\\(|GlobalScope|@Scheduled|ScheduledExecutorService|Executors\\.|scheduleAtFixedRate|Timer\\(|System\\.currentTimeMillis|Instant\\.now|Clock\\.system|CoroutineScope\\(|launch\\(|async\\(" leader/tenant-scheduler/src; then exit 1; fi
if rg -n "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\b(?:acct|aws|customer)-[0-9]{6,}\\b" leader/tenant-scheduler README.md README.ko.md docs/images/readme-diagrams; then exit 1; fi
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml
git diff --check
```

## 수락 매핑

| 발행 요구사항 | 플랜 적용 범위 |
|-------------------|---------------|
| Tenant/shard-based 리더 잠금 이름 | `TenantLockNamespace`을 통한 테넌트 범위 잠금 이름;  #329의 범위를 명시적으로 벗어난 샤드 추상화. |
| 독립적인 예정된 작업 | 작업 4와 8은 두 테넌트가 독립적으로 진행됨을 증명합니다. |
| 공정성 | 작업 4 제한된 용량을 `lastSelectedTick` 기준으로 정렬한 다음 테넌트 별칭을 기준으로 정렬합니다. |
| 오래된 잠금 처리 | 작업 4 active/expiry/handoff 테스트입니다. |
| 테넌트별 metrics/tags | 작업 3 제한된 측정항목 태그 정책 및 README 지침. |
| 기본 테스트는 로컬 및 결정적 | 작업 1-4에서는 타이머, 폴링, Awaitility, Testcontainers 및 백엔드를 금지합니다. |
| 소스에 해당하는 이중 언어 README | 작업 5 parity/language는 콘텐츠 요구 사항을 확인하고 일치시킵니다. |
| 테스트된 README 조각 | 작업 5에는 `TenantSchedulerReadmeSnippetTest`에서 복사된 조각이 필요합니다. |
| 실험실 대 생산 분산 잠금 경계 | 작업 5와 6에는 명시적인 README 및 다이어그램 경계 레이블이 필요합니다. |
| 이해할 수 있고 체크리스트를 통과한 다이어그램 | 작업 6 전체 다이어그램 QA 및 육안 검사. |
| 레포 등록 | 작업 7 프로젝트, 연기 존재, 전체 연기, 작업 흐름 및 오래된 검사. |

## 위험 및 통제

| 위험 | 제어 |
|------|---------|
| 예제는 프로덕션 분산 잠금으로 잘못되었습니다. | README 및 KDoc에서는 랩 모델이 관찰 가능한 스케줄링 동작만을 모델로 한다고 명시합니다. 실제 백엔드 모듈이 연결되어 있습니다. |
| 측정항목 태그는 높은 카디널리티 테넌트 ID를 학습합니다. 식별자 문법 및 측정항목 정책은 제한되지 않은 테넌트별 행을 억제합니다. |
| 테넌트 오류로 인해 실수로 전체 틱이 중단됨 | 감속기 테스트는 실패 후 나중에 테넌트 평가를 검증문합니다. |
| 오래된 핸드오프 경계가 모호함 | 핀 `currentTick < expiresAtTick` 활성 및 `currentTick >= expiresAtTick` 핸드오프를 테스트합니다. |
| 다이어그램이 스크립트를 통과했지만 시각적 품질에 실패함 | 삽입하기 전에 전체 크기로 렌더링된 PNG 검사가 필요합니다. |
| CI에서 새 모듈이 누락됨 | 작업 7에서는 프로젝트 목록, 연기 스크립트, 예제 워크플로 및 오래된 확인을 다룹니다. |
