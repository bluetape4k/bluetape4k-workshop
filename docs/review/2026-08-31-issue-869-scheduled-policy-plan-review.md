# Issue #869 구현 계획 Step 3-R 통합 검토

## 검토 범위와 기준

- 대상 계획: `docs/superpowers/plans/2026-08-31-issue-869-scheduled-policy-plan.md`
- 기반 사양: `docs/superpowers/specs/2026-08-31-issue-869-scheduled-policy-design.md`
- 사양·선행 검토 커밋: `ab708c0f47a25b9e4f5b6882470ebc99c46a4b94`
- 검토 방식: performance, stability, security, operator/ops, developer/API, user/caller 여섯 독립 렌즈와 main integration
- 판정 규칙: 최신 통합 결과에서 P0=0, P1=0이어야 구현 단계로 이동한다. P2/P3는 수정·upstream 위임·후속 hardening 중 하나로 처분을 기록한다.

## 여섯 관점 결과

| 관점 | 최초 판정 | 계획 반영 및 최신 통합 판정 | 근거·처분 |
|---|---:|---:|---|
| Performance | P0=0, P1=0, P2=3, P3=1 | P0=0, P1=0, P2=0, P3=0 | 실제 scheduler trigger를 immediate fixture로 분리하고, cadence·lease-floor 지연을 문서화했다. direct 호출은 두 번의 관찰 가능한 성공만 확인하며 upstream cache 내부 hit/miss는 `N/A (upstream cache contract)`로 제한했다. bounded timeout과 CI job 상한을 고정했다. |
| Stability | P0=0, P1=2, P2=5 | P0=0, P1=0, P2=0, P3=0 | `aop.enabled=false` 상태를 bean/task 단위로 고정하고, pending/in-flight close에 독립 2초 body timeout·5초 close assertion·`@Timeout(10)`·finally release를 명시했다. 외부 override 제거 rollback과 observation lifecycle upstream 위임을 추가했다. |
| Security | P0=0, P1=0, P2=4 | P0=0, P1=0, P2=0, P3=0 | 기본 profile의 외부 `scheduling.enabled=true` fail-closed, tracing 기본값(`include-lock-name`, `include-leader-id`, `include-exception-details`), `REDACT`, static non-PII name을 acceptance로 고정했다. 동적 SpEL/placeholder 실행 세부사항과 checksum metadata는 각각 upstream 계약·후속 공급망 hardening으로 명시했다. |
| Operator/Ops | P0=0, P1=1, P2=2 | P0=0, P1=0, P2=0, P3=0 | rollback 첫 단계에 외부 override 제거를 넣고, catalog의 실제 Spring Boot 4.1.0을 기준으로 수정했다. README parity/stale guard를 checkout·Java 25·Gradle setup·10분 job timeout이 있는 별도 workflow job으로 실행하며, 기존 module path/test/artifact 등록은 중복하지 않는다. |
| Developer/API | P0=0, P1=2, P2=2 | P0=0, P1=0, P2=0, P3=0 | non-NOOP `ObservationRegistry`, handler, `ObservationAutoConfiguration`과 5개 leader auto-configuration을 명시했다. 실제 auto-configuration 순서는 `AutoConfiguration.imports`와 upstream order assertion으로 검증하고, `AutoConfigurations.of` 인자 순서는 로딩 대상 지정으로만 취급한다. ConfigData source 이름은 진단용으로 격하하고 resource 원문·산출물·inline 부재를 acceptance로 삼았다. in-flight close는 독립 bounded wait와 JUnit timeout으로 보강했다. |
| User/Caller | P0=0, P1=1, P2=2 | P0=0, P1=0, P2=0, P3=0 | 양쪽 module README에 versionless dependency/plugin snippet과 stale guard를 추가하고, startup·첫 callback 지연·Observation 확인 경로·trusted static logger 경계를 안내한다. |

## 통합 findings와 disposition

| 우선순위 | finding | 계획의 정확한 처분 |
|---|---|---|
| P1 | AOP opt-out 결과가 모호할 수 있음 | 작업 2D에서 fixture/task는 남고 factory/registry/BPP/aspect는 없음을 정확히 assertion |
| P1 | Observation 검증 wiring이 불명확할 수 있음 | 작업 2B에서 non-NOOP registry, handler, `ObservationAutoConfiguration`, `LeaderMicrometerAutoConfiguration`, `LeaderObservationAutoConfiguration` 및 leader auto-config을 고정 |
| P1 | in-flight close가 release 이후에만 안전해질 수 있음 | 작업 2E에서 body 독립 2초 bounded wait, close 5초, test `@Timeout(10)`, finally release 순서를 고정 |
| P1 | README를 복사하면 CTW/dependency가 누락됨 | 작업 6A에서 `leader-spring-boot`, `leader-micrometer`, Freefair plugin versionless snippet과 stale guard를 추가 |
| P2 | scheduler callback/cadence의 관찰성 부족 | immediate trigger는 lifecycle test로, cadence와 60초 initial delay는 README/runbook으로 분리 |
| P2 | upstream cache hit/miss를 consumer가 직접 입증할 수 없음 | 두 번의 direct invocation과 동일 Observation만 검증하고 cache 내부는 upstream 위임 |
| P2 | external backend failure semantics를 consumer가 재현할 수 없음 | `SKIP` 기본값·`FAIL_OPEN_RUN` 위험을 문서화하고 upstream failure-mode tests로 위임, capability는 N/A |
| P2 | plugin artifact checksum metadata 부재 | buildEnvironment/dependencyInsight component provenance를 lesson에 기록하고 checksum은 후속 hardening으로 분리 |
| P1 | 신규 workflow guard가 독립 runner 준비 단계를 생략할 수 있음 | 작업 6C에 `checkout@v4`, `setup-java@v4` Java 25 Temurin, `setup-gradle@v4`, `timeout-minutes: 10`과 wrapper 실행 환경을 명시 |
| P3 | Observation registration cleanup의 consumer 추적 부재 | upstream recorder/coordinator lifecycle 책임으로 `N/A (upstream delegated)` 명시 |

통합 결과는 **P0=0, P1=0, P2=0, P3=0**이다. P2/P3는 구현 acceptance, 문서 경계, upstream 위임, 후속 hardening 중 하나로 추적되며 미처리 finding으로 남지 않는다.

## Step 3-R required checks

| # | 검사 | 결과 | 계획 근거 |
|---:|---|---|---|
| 1 | 사양·DoD → concrete task 매핑 | PASS | 수용 기준 추적표와 작업 1~8 |
| 2 | 현재 codebase에 실행 가능한 순서 | PASS | catalog/classpath → red test → main config/YAML → green → docs/guard → full verify |
| 3 | 후행 산출물 선행 의존성 없음 | PASS | 의존성 순서와 재실행 규칙에 고정 |
| 4 | 성공·실패·edge·concurrency·lifecycle·backend capability | PASS | context/runner, invalid binding, immediate/pending/in-flight lifecycle; coroutine·external backend는 N/A/upstream |
| 5 | concrete verification command | PASS | Gradle compile/test/clean no-build-cache, parity, stale-check, dependencyInsight |
| 6 | README 및 locale README | PASS | module/root README pair와 parity script |
| 7 | 한국어 KDoc/comment/PR/lesson 범위 | PASS | 작업 3 KDoc, 작업 7 lesson, 작업 8 Korean PR/commit contract |
| 8 | 새 module settings/BOM/CI/nightly/coverage | N/A | 새 module이 아니며 기존 settings/workflow 등록을 재사용; coverage row만 갱신 |
| 9 | Spring Boot conditional guard/import ordering | PASS | 작업 2C runner, `AutoConfiguration.imports`, 작업 3 profile/conditional 확인 |
| 10 | Exposed import/receiver | N/A | Exposed 변경 없음 |
| 11 | coroutine cancellation/dispatcher | N/A | suspend/coroutine API 변경 없음 |
| 12 | 성능·blocking·cleanup·polling | PASS | lease-floor blocking 문서, bounded waits, no custom thread, CI timeout |
| 13 | cross-module duplication 결정 | PASS | 기존 tenant reducer는 보존하고 scheduled integration만 같은 module의 별도 profile로 추가; upstream behavior는 재구현하지 않음 |
| 14 | rollback/compatibility/migration | PASS | 외부 override 제거 → profile 비활성화 → 파일/의존성 rollback → 재기동 → bean/task 부재 확인 |

조건부 검사는 다음과 같이 처분했다.

- coroutine/suspend cancellation: `N/A (이 issue는 일반 `@Scheduled` 메서드만 사용)`
- 새 module settings 등록: `N/A (기존 module 확장)`
- Exposed import/receiver: `N/A (Exposed 미사용)`
- JDK preview: `N/A (Java 25 stable API만 사용)`
- external backend capability: `N/A (local factory consumer wiring이며 upstream failure-mode contract 위임)`

## 문서 품질과 DoD 추적

- `SPW-01`: 문제·독자·목표를 plan header와 작업 6 README acceptance에 기록
- `SPW-02`: current state와 upstream/repository evidence를 작업 1·2 및 spec review에 기록
- `SPW-03`: exact catalog/plugin/dependency, Kotlin config, YAML, fixture, test wiring을 작업 1~4에 기록
- `SPW-04`: red/green/full verification, CTW, lifecycle, stale/parity checks를 작업 2·5·7에 기록
- `SPW-05`: rollback, failure recovery, Lore commit/PR gate를 작업 7·8에 기록
- `KO-01` 목적/독자: module 사용자와 contributor를 README/plan에서 구분
- `KO-02` 용어: API·property·selector·command는 원문 보존, 설명은 한국어
- `KO-03` 문장: 짧은 acceptance와 표 중심으로 작성
- `KO-04` 코드/명령: 실행 가능한 exact snippet과 기대 결과 유지
- `KO-05` locale: README 두 파일의 heading/code fence/key/숫자 parity
- `KO-06` 안전성: PII, raw lock name, exception detail, `FAIL_OPEN_RUN` 경계 명시
- `KO-07` 검증: Korean terminology audit와 `git diff --check`를 작업 7에 포함

## 통합 결론과 승인 게이트

계획 문서와 본 검토 문서는 구현 전에 별도 Lore 커밋으로 고정한다. 현재 코드는 변경하지 않았으며, 다음 단계는 사용자가 계획을 검토하고 `승인`하는 것이다. 계획 승인 전에는 작업 1의 catalog/plugin 변경과 TDD red test를 시작하지 않는다.

통합 상태: **PASS — P0=0, P1=0; 구현 대기 `PENDING (plan approval required)`**
