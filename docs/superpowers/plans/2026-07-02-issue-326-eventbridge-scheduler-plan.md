# Issue 326 EventBridge 스케줄러 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** EventBridge 봉투 매핑 및 스케줄러 스타일 지연된 워크플로 경계를 가르치는 로컬 우선 `aws/eventbridge-scheduler` 워크숍 모듈을 구축합니다.

**아키텍처:** Spring Boot 예제 서비스는 `OrderWorkflowRequest`을 EventBridge 이벤트와 로컬 스케줄러 요청에 매핑합니다. EventBridge는 `bluetape4k-dependencies 1.3.1` 아티팩트가 아직 최신 `bluetape4k-aws` EventBridge Spring 래퍼를 노출하지 않기 때문에 워크샵 로컬 `EventBridgePublisher` 뒤에서 AWS SDK v2 `PutEventsRequestEntry`를 사용합니다. 또한 업스트림 Scheduler 래퍼 지원을 아직 사용할 수 없기 때문에 Scheduler는 워크샵-로컬 경계를 유지합니다.

**기술 스택:** Kotlin 2.3 언어 수준, Java 21, Spring Boot 4, AWS SDK v2 EventBridge, Jackson 3, bluetape4k core/assertions/JUnit5/coroutines 테스트 도우미.

---

## 파일 구조

- `aws/eventbridge-scheduler/build.gradle.kts`를 생성합니다.
- `aws/eventbridge-scheduler/src/main/kotlin/io/bluetape4k/workshop/aws/eventbridge/`를 생성합니다.
- `EventBridgeSchedulerApplication.kt`, `OrderWorkflowModels.kt`, `OrderWorkflowProperties.kt`, `WorkflowScheduler.kt`, `LocalWorkflowScheduler.kt`, `OrderWorkflowService.kt`를 만듭니다.
- `src/test/kotlin/.../eventbridge/`과 일치하는 테스트를 만듭니다.
- `src/main/resources/application.yml`, `src/test/resources/junit-platform.properties` 및 `src/test/resources/logback-test.xml`를 생성합니다.
- `gradle/libs.versions.toml`을 `aws2-eventbridge-lib`로만 업데이트하세요.
- `aws/README.md`, `aws/README.ko.md`, AWS 모듈 인덱스가 있는 경우 루트 README 로케일 세트, `scripts/smoke-validate.sh`, `.github/workflows/Examples.yml` 및 다이어그램 유효성 검사기 스크립트를 업데이트합니다.
- `docs/images/readme-diagrams/` 아래에 다이어그램 SVG/PNG 자산을 만듭니다.
- `docs/review/` 및 `docs/lessons/` 아래에 복습 및 강의 아티팩트를 생성합니다.

## 작업

### 작업 1: 워크플로 매핑을 위한 RED 테스트 추가

- [x] 다음에 대한 테스트를 사용하여 `OrderWorkflowServiceTest.kt`을 만듭니다.
  - EventBridge 항목 소스, 세부 정보 유형, 이벤트 버스, JSON 세부 정보 및 추적 헤더.
  - 스케줄러 일정 name/group/target/expression/payload.
  - 멱등성 키 및 상관 ID 전파.
  - EventBridge 실패하면 스케줄러를 건너뜁니다.
  - 스케줄러 실패로 인해 EventBridge이 계속 게시되지만 스케줄러 실패가 보고됩니다.
  - `CancellationException`이(가) 다시 발생합니다.
- [x] `./gradlew :aws-eventbridge-scheduler:test --tests "*OrderWorkflowServiceTest"`를 실행하세요.
- [x] 예상 RED: 구현 전에 프로젝트 또는 클래스가 해결되지 않았습니다.

### 작업 2: 최소 모듈 구현

- [x] `aws/cloudwatch-imds-observability`의 Spring Boot 규칙을 사용하여 Gradle 모듈을 추가합니다.
- [x] `Serializable` 및 `serialVersionUID`을 사용하여 모델 데이터 클래스를 추가합니다.
- [x] 로컬 기본값으로 `OrderWorkflowProperties`을 추가합니다.
- [x] `EventBridgePublisher`, `WorkflowScheduler` 및 로컬 캡처 구현을 추가합니다.
- [x] 유효성 검사 도우미, 취소 다시 발생, EventBridge 매핑, 스케줄러 매핑 및 실패 보고를 사용하여 `OrderWorkflowService`을 추가합니다.
- [x] application/resources/test 리소스를 추가합니다.
- [x] 녹색이 될 때까지 대상 테스트를 실행합니다.

### 작업 3: README 및 다이어그램 추가

- [x] 언어 스위치를 사용하여 `README.md` 및 `README.ko.md` 모듈을 추가합니다.
- [x] 모듈 guide/runtime/run 항목으로 설정된 AWS README 로케일을 업데이트합니다.
- [x] AWS 모듈을 색인화하는 경우 루트 README 로케일 세트를 업데이트하십시오.
- [x] 아키텍처 및 시퀀스 SVG를 추가하고 CairoSVG을 사용하여 PNG를 렌더링합니다.
- [x] 생성된 `*-readme-architecture-01.svg` 및 `*-readme-sequence-01.svg`을 확인합니다.
  이름은 레거시 예외 없이 저장소 유효성 검사기에 의해 보호됩니다.
- [x] XML 구문 분석, 렌더링, marker/color/style, 커넥터 형상, 시퀀스 스타일 및 전체 크기 육안 검사를 포함한 전체 `$bluetape4k-diagram` 체크리스트를 실행합니다.

### 작업 4: 등록 유효성 검사 및 CI

- [x] `scripts/smoke-validate.sh` `all-smoke`, `aws` 및 `stale-check` 예상 프로젝트 수를 업데이트하세요.
- [x] `.github/workflows/Examples.yml` 경로 필터, 연기 테스트 명령, 아티팩트 경로 및 주석을 업데이트합니다.
- [x] `actionlint .github/workflows/Examples.yml`를 실행하세요.

### 작업 5: 확인

- [x] `./gradlew :aws-eventbridge-scheduler:compileKotlin :aws-eventbridge-scheduler:compileTestKotlin --warning-mode all --max-workers=1`를 실행하세요.
- [x] `./gradlew :aws-eventbridge-scheduler:test --no-build-cache --rerun-tasks --max-workers=1`를 실행하세요.
- [x] `./scripts/smoke-validate.sh aws`를 실행하세요.
- [x] `./scripts/smoke-validate.sh stale-check`를 실행하세요.
- [x] `node scripts/validate-readme-parity.mjs`를 실행하세요.
- [x] `node scripts/validate-readme-language.mjs`를 실행하세요.
- [x] 명시적인 새 SVG 경로와 동등한 `./scripts/smoke-validate.sh diagram-qa`을 실행합니다.
- [x] `git diff --check`를 실행하세요.

### 작업 6: 복습, 강의, PR, CI

- [x] P0/P1 수렴을 사용하여 6-R단계 7계층 검토를 실행합니다.
- [x] `docs/review/2026-07-02-issue-326-eventbridge-scheduler-review.md`를 저장하세요.
- [x] `docs/lessons/2026-07-02-issue-326-eventbridge-scheduler.md`을 추가합니다.
- [ ] Lore 예고편으로 커밋하세요.
- [ ] 본문이 `## DoD Status`으로 끝나는 PR를 생성합니다.
- [ ] PR 마일스톤, 담당자, 라벨, 본문 및 CI 확인을 확인합니다.

## 계획 자체 검토

- 사양 범위: 모든 이슈 #326 승인 기준은 작업 1-5에 매핑됩니다.
- 자리 표시자 스캔: 구현 단계로 TODO/TBD 자리 표시자가 남지 않습니다.
- 유형 일관성: model/service 이름은 `OrderWorkflow*`, `WorkflowScheduler`을 사용합니다.
  그리고 `EventBridgeScheduler` 일관되게.
- 위험: 스케줄러 실제 AWS 통합은 다음까지 명시적으로 범위를 벗어납니다.
  `bluetape4k-aws`발행 #310토지.
