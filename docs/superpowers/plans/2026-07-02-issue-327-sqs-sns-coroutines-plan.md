# Issue 327 SQS/SNS 코루틴 메시징 구현 계획

> **For agentic workers:** implement one task at a time and update this
> 증거 수집용 체크리스트입니다. 없이 다이어그램 게이트를 완료로 표시하지 마십시오.
> 구체적인 `$bluetape4k-diagram` 원장 출력 및 전체 크기 PNG 검사.

**목표:** 로컬 우선 `aws/sqs-sns-coroutines` 워크샵 모듈을 구축하세요.
SNS 게시, SQS 코루틴 소비, retry/dead-letter 가르치기
분류 및 Micrometer 결과 지표.

**아키텍처:** Spring Boot 서비스 코드는 `SnsOperations`을 사용하고
`bluetape4k-aws-spring-boot`의 `SqsOperations`. 기본 런타임은 다음을 사용합니다.
조건부 인메모리 로컬 작업, 통합 테스트에서는
`FlociServer.Launcher.floci` 실제 bluetape4k 코루틴 작업 템플릿 사용
따라서 모듈에는 AWS 자격 증명이 필요하지 않습니다.

**기술 스택:** Kotlin 2.4, Java 21, Spring Boot 4, bluetape4k core/assertions,
bluetape4k-aws Spring Boot SQS/SNS 작업, `bluetape4k-jackson3`
`Jackson.defaultJsonMapper`, Micrometer 코어, 대기 `untilSuspending`,
Floci/Testcontainers, JUnit 5, MockK 유용합니다.

## 파일 구조

- `aws/sqs-sns-coroutines/build.gradle.kts`를 생성합니다.
- `aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/`를 생성합니다.
- 애플리케이션, 속성, 모델, 메트릭, 핸들러, 서비스 및 로컬 생성
  작업 구성 파일.
- `src/test/kotlin/.../sqssns/`과 일치하는 테스트를 만듭니다.
- `src/main/resources/application.yml`, `src/test/resources/junit-platform.properties` 생성,
  그리고 `src/test/resources/logback-test.xml`.
- 필요한 경우 AWS SDK v2 SQS/SNS 별칭으로 `gradle/libs.versions.toml`을 업데이트하세요.
- module/root README 로케일 세트, `scripts/smoke-validate.sh` 및 업데이트
  `.github/workflows/Examples.yml`.
- `docs/images/readme-diagrams/` 아래에 SVG/PNG 다이어그램을 만듭니다.
- `docs/review/` 및 `docs/lessons/` 아래에 복습 및 강의 아티팩트를 생성합니다.

## 작업

### 작업 1: publish/consume 동작에 대한 RED 테스트 추가

- [x] 다음에 대한 테스트를 사용하여 `OrderNotificationMessagingServiceTest.kt`을 만듭니다.
  - SNS 게시 요청 주제 ARN, 제목, JSON 본문, 멱등성 키 및 상관관계 ID.
  - SQS 소비 성공은 메시지를 삭제하고 `acked`을 기록합니다.
  - 최대 수신 횟수가 가시성을 변경하고 `retry`을 기록하기 전에 핸들러 오류가 발생했습니다.
  - 최대 수신 횟수는 `dead-letter`으로 분류됩니다.
  - 측정항목 counters/timers에는 안정적인 낮은 카디널리티 태그가 포함됩니다.
  - `CancellationException`이(가) 다시 발생합니다.
- [x] `./gradlew :aws-sqs-sns-coroutines:test --tests "*OrderNotificationMessagingServiceTest"`를 실행하세요.
- [x] 예상 RED: module/classes이(가) 구현 전에 해결되지 않았습니다.

### 작업 2: 모듈 구현

- [x] 기존 AWS 모듈의 Spring Boot 규칙을 사용하여 Gradle 모듈을 추가합니다.
- [x] `Serializable` 및 `serialVersionUID`을 사용하여 모델 데이터 클래스를 추가합니다.
- [x] 로컬 안전 기본값으로 `SqsSnsMessagingProperties`을 추가합니다.
- [x] `OrderNotificationMetrics` 도우미를 추가합니다.
- [x] `JacksonMessagingConfig`을 `Jackson.defaultJsonMapper`에 추가합니다.
- [x] 유효성 검사 도우미를 사용하여 `OrderNotificationMessagingService`을 추가합니다.
  취소 다시 던지기, SNS 매핑, SQS 처리, retry/dead-letter
  분류 및 지표.
- [x] 기본값으로 로컬 인메모리 `SnsOperations` 및 `SqsOperations` Bean을 추가합니다.
  실제 빈이 없는 경우에만 런타임이 실행됩니다.
- [x] `OrderNotificationFlociIntegrationTest`을 `FlociServer.Launcher.floci`에 추가하고,
  실제 `SnsCoroutinesTemplate`/`SqsCoroutinesTemplate` 및 대기
  `untilSuspending` 폴링.
- [x] 녹색이 될 때까지 대상 테스트를 실행합니다.

### 작업 3: README 및 다이어그램 추가

- [x] 언어 스위치를 사용하여 `README.md` 및 `README.ko.md` 모듈을 추가합니다.
- [x] AWS README 로케일 세트 및 루트 README 로케일 세트를 업데이트합니다.
- [x] 아키텍처 및 시퀀스 SVG를 추가하고 CairoSVG을 사용하여 PNG를 렌더링합니다.
- [x] 지역 카탈로그의 AWS 공식 SNS/SQS 아이콘을 사용하세요.
- [x] XML 구문 분석, 렌더링,
  marker/color/style, 커넥터 형상, 시퀀스 스타일 및 전체 크기 시각적 개체
  점검.

### 작업 4: 등록 유효성 검사 및 CI

- [x] `scripts/smoke-validate.sh` `all-smoke`, `aws` 및 `stale-check` 업데이트
  예상 프로젝트 수; Floci 지원 모듈을 컨테이너가 아닌 곳에 두십시오.
  `all-smoke`.
- [x] `.github/workflows/Examples.yml` 경로 필터 업데이트, smoke/container
  명령, 아티팩트 경로 및 주석.
- [x] `actionlint .github/workflows/Examples.yml`를 실행하세요.

### 작업 5: 확인

- [x] `./gradlew :aws-sqs-sns-coroutines:compileKotlin :aws-sqs-sns-coroutines:compileTestKotlin --warning-mode all --max-workers=1`를 실행하세요.
- [x] `./gradlew :aws-sqs-sns-coroutines:test --no-build-cache --rerun-tasks --max-workers=1`를 실행하세요.
- [x] `./scripts/smoke-validate.sh aws`를 실행하세요.
- [x] `./scripts/smoke-validate.sh stale-check`를 실행하세요.
- [x] `node scripts/validate-readme-parity.mjs`를 실행하세요.
- [x] `node scripts/validate-readme-language.mjs`를 실행하세요.
- [x] `./scripts/smoke-validate.sh diagram-qa` 또는 대상 래퍼 명령을 실행합니다.
- [x] `git diff --check`를 실행하세요.

### 작업 6: 복습, 강의, PR, CI

- [x] P0/P1 수렴을 사용하여 6-R단계 7계층 검토를 실행합니다.
- [x] `docs/review/2026-07-02-issue-327-sqs-sns-coroutines-review.md`를 저장하세요.
- [x] `docs/lessons/2026-07-02-issue-327-sqs-sns-coroutines.md`을 추가합니다.
- [x] Lore 예고편으로 커밋하세요.
- [x] 본문이 `## DoD Status`으로 끝나는 PR를 생성합니다.
- [x] PR 마일스톤, 담당자, 라벨, 본문 및 CI 확인을 확인합니다.

## 검증 증거

- 대상 컴파일: `./gradlew :aws-sqs-sns-coroutines:compileKotlin
  :aws-sqs-sns-coroutines:compileTestKotlin --warning-mode all --max-workers=1
  --console=일반` -> `BUILD SUCCESSFUL`.
- 테스트 다시 실행: `./gradlew :aws-sqs-sns-coroutines:test --no-build-cache
  --rerun-tasks --max-workers=1 --console=plain` -> 8개 테스트 통과,
  `OrderNotificationFlociIntegrationTest`, `BUILD SUCCESSFUL` 포함.
- AWS 연기: `./scripts/smoke-validate.sh aws` -> `BUILD SUCCESSFUL`;
  `aws-sqs-sns-coroutines`은 Floci 통합 테스트를 포함해 8가지 테스트를 실행했습니다.
- 모든 연기: `./scripts/smoke-validate.sh all-smoke` -> `BUILD SUCCESSFUL`.
- 오래된 검사: 활성 모듈 `97 (expected: 97)`, 오래된 참조 없음, 깨진 항목 없음
  이미지 링크.
- README parity/language: `failures=0`, `offenders=0`, `totalHits=0`.
- 다이어그램 QA: 전달된 아키텍처 및 시퀀스 SVG에 대한 대상 래퍼
  `targets=2 weak_reference_rows=0`으로; 전체 크기 PNG 육안 검사를 통과했습니다.
- Workflow/whitespace: `actionlint .github/workflows/Examples.yml` 및
  `git diff --check`은(는) 출력을 생성하지 않았습니다.

## 계획 자체 검토

- 사양 범위: 모든 이슈 #327 승인 기준은 작업 1-5에 매핑됩니다.
- 자리 표시자 스캔: 구현 단계로 TODO/TBD 자리 표시자가 남지 않습니다.
- 유형 일관성: 모듈, 패키지 및 클래스 이름이 일관되게 사용됩니다.
  `sqs-sns-coroutines`, `sqssns`, `OrderNotification*`.
- 위험: 실제 AWS은 사용되지 않습니다. Floci/Testcontainers 유효성 검사가 기본값입니다.
  이 모듈의 `test` 작업은 순차적 컨테이너 지원 레인에 보관됩니다.
