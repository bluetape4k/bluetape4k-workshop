# CloudWatch IMDS 관찰성 워크숍 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 학습자 친화적인 Spring Boot AWS 관찰 가능성 예시를 추가합니다.
CloudWatch 지표, CloudWatch 로그, Micrometer를 보여주는 이슈 #317
스냅샷 게시 및 실제 AWS 자격 증명 없이 명시적으로 안전한 IMDS 읽기
기본 테스트에서.

**사양:** `docs/superpowers/specs/2026-06-30-issue-317-cloudwatch-imds-observability-design.md`

**사양 검토:** `docs/review/2026-06-30-issue-317-spec-review.md`

**아키텍처:** 로컬 우선 Spring Boot MVC/Actuator 모듈은 작은
원격 측정 API. 서비스는 로컬 Micrometer 상태를 기록하고 CloudWatch을 빌드합니다.
metric/log 요청하고 선택적으로 안전한 IMDS 도우미 값을 읽고
게시되거나 건너뛴 내용을 설명하는 보고서입니다. 로컬 프로필 빈은
결정적이고 자격 증명이 필요하지 않습니다. 선택적 실제 AWS 사용법은 다음과 같이 문서화되어 있습니다.
수동 프로필만 가능합니다.

**기술 스택:** Kotlin, Spring Boot 4 MVC/Actuator, bluetape4k-aws Spring
인터페이스, AWS SDK v2 CloudWatch/CloudWatch Logs/IMDS 모델, Micrometer,
JUnit 5, MockK, bluetape4k 어설션.

---

## 파일 구조

- `aws/cloudwatch-imds-observability/build.gradle.kts` 생성
- `aws/cloudwatch-imds-observability/src/main/kotlin/io/bluetape4k/workshop/aws/observability/*` 생성
- `aws/cloudwatch-imds-observability/src/main/resources/application.yml` 생성
- `aws/cloudwatch-imds-observability/src/test/kotlin/io/bluetape4k/workshop/aws/observability/*` 생성
- `aws/cloudwatch-imds-observability/src/test/resources/junit-platform.properties` 생성
- `aws/cloudwatch-imds-observability/src/test/resources/logback-test.xml` 생성
- `aws/cloudwatch-imds-observability/README.md` 생성
- `aws/cloudwatch-imds-observability/README.ko.md` 생성
- `gradle/libs.versions.toml` 수정
- `aws/README.md` 수정
- `aws/README.ko.md` 수정
- 루트 `README.md` 수정
- 루트 `README.ko.md` 수정
- `.github/workflows/Examples.yml` 수정
- `scripts/smoke-validate.sh` 수정
- `docs/images/readme-diagrams/` 아래에 SVG/PNG 다이어그램 만들기
- 구현 검토 후 `docs/lessons/` 아래에 짧은 강의 만들기

## 종속성 및 API 가드

- [ ] AWS SDK v2 모듈에 대한 버전 없는 버전 카탈로그 라이브러리 별칭을 추가합니다.
      `aws2-cloudwatch-lib`, `aws2-cloudwatchlogs-lib`, `aws2-imds-lib`.
- [ ] 루트 `bluetape4k-dependencies` BOM만 사용하세요. 아무것도 추가하지 마세요
      저장소-로컬 bluetape4k 버전.
- [ ] 해결된 `bluetape4k-aws` 아티팩트 API에 대해 컴파일을 확인합니다.
      유일한 형제 소스 증거.
- [ ] 모듈에서 사용되는 공개 작업 및 모델 유형을 확인합니다.
      `CloudWatchOperations`, `CloudWatchLogsOperations`,
      `CloudWatchMeterPublishingOperations`, `ImdsOperations`,
      `MetricDatum`, `PutLogEventsRequest`, `InputLogEvent`.

## 작업

### 작업 1: 모듈 뼈대

- [ ] `kotlin.spring`, Spring Boot MVC/Actuator를 사용하여 `build.gradle.kts`를 생성합니다.
      구성 프로세서, bluetape4k-aws, AWS SDK CloudWatch/Logs/IMDS,
      Micrometer, MockK, JUnit 5, bluetape4k 어설션 및 Spring Boot 테스트
      의존성.
- [ ] `ObservabilityApplication.kt`을 추가합니다.
- [ ] 로컬 기본값으로 `application.yml`을 추가합니다.
      `bluetape4k.workshop.aws.observability.namespace`,
      `logGroupName`, `logStreamName`, `serviceName` 및
      `metadata.enabled=false`.
- [ ] 기존 Spring Boot 모듈과 일치하는 테스트 리소스를 추가합니다.
- [ ] `./gradlew projects --console=plain`을 실행하고 확인합니다.
      `:aws-cloudwatch-imds-observability`은 `includeModules("aws", false, true)`을 통해 나타납니다.
- [ ] `./gradlew :aws-cloudwatch-imds-observability:compileKotlin --warning-mode all --console=plain`를 실행하세요.

### 작업 2: TDD 빨간색 테스트

- [ ] 성공적인 운영을 위해 프로덕션 구현 전에 실패한 서비스 테스트를 추가합니다.
      원격 측정 게시.
- [ ] CloudWatch 측정항목 데이텀 이름, 네임스페이스, 차원에 대한 실패한 테스트를 추가합니다.
      (`Outcome`, `Service`, `Source`만) 및 값 매핑.
- [ ] CloudWatch 로그 그룹, 스트림, 타임스탬프, 삭제된 로그에 대해 실패한 테스트를 추가합니다.
      JSON 이벤트 필드 및 민감한 요청 데이터가 없습니다.
- [ ] Micrometer counter/timer 증분에 대해 실패한 테스트를 추가하고 선택했습니다.
      미터 스냅샷 게시.
- [ ] 메트릭 게시 실패, 로그 게시 실패, 미터에 대한 실패한 테스트를 추가합니다.
      스냅샷 실패 및 혼합된 부분 실패 보고서.
- [ ] `CancellationException`이 일시 중지 상태에서 다시 발생했음을 증명하는 실패한 테스트를 추가합니다.
      게시 경로.
- [ ] IMDS 기본 건너뛰기 동작에 대해 실패한 테스트를 추가합니다.
- [ ] 명시적 메타데이터 선택 읽기 전용 안전 도우미에 대한 실패한 테스트를 추가합니다.
      인스턴스 ID, 지역, 가용성 영역과 같은 값입니다.
- [ ] 다음과 같은 자격 증명 문서 경로를 검증하는 실패한 테스트를 추가합니다.
      `/latest/meta-data/iam/security-credentials/{role}`은 절대 읽히지 않습니다.
- [ ] `./gradlew :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain` 실행
      구현하기 전에 예상되는 빨간색 오류를 기록합니다.

### 작업 3: 도메인 및 속성

- [ ] 직렬화 가능한 DTO 및 모델을 추가합니다.
      `OrderTelemetryRequest`, `OrderTelemetryReport`, `TelemetryOutcome`,
      `PublishStatus`, `MetadataSnapshot`, `TelemetryFailure`.
- [ ] 모든 새로운 Kotlin `data class`은 `java.io.Serializable`을 구현해야 하며
      `serialVersionUID`을 정의하세요.
- [ ] 네임스페이스, 로그 그룹, 로그 스트림이 포함된 `AwsObservabilityProperties`을 추가합니다.
      서비스 이름, 소스 이름, 메타데이터 옵트인 플래그 및 최대 자유 형식 필드
      길이.
- [ ] bluetape4k 검증 도우미를 사용하여 호출자 제어 문자열을 검증합니다.
- [ ] 부분 실패 의미 체계를 정의합니다.
      metric/log/meter 오류는 독립적으로 보고되고, 메타데이터 오류는
      메타데이터 상태 내에 머무르며 취소가 절대 삼켜지지 않습니다.

### 작업 4: 로컬 작업 Bean

- [ ] default/local 프로필에 `LocalAwsObservabilityConfig`을 추가합니다.
- [ ] 결정적인 로컬 구현을 제공하거나 가짜 테스트를 제공합니다.
      `CloudWatchOperations`, `CloudWatchLogsOperations`,
      `CloudWatchMeterPublishingOperations` 및 `ImdsOperations`.
- [ ] 로컬 Bean이 AWS SDK 클라이언트를 생성하지 않고 자격 증명을 읽지 않도록 확인합니다.
      IMDS을 호출하지 않으며 CI에 적합합니다.
- [ ] 캡처된 요청 상태를 공개로 전환하지 않고도 테스트 가능한 상태로 유지합니다.
      생산 API.
- [ ] 선택적 실제 프로필 클래스가 추가된 경우 이를 명시적으로 보호하세요.
      profile/property 조건과 CI이(가) 실행되지 않는 문서입니다.

### 작업 5: 원격 측정 서비스 및 HTTP 경계

- [ ] Micrometer timer/counter 상태를 기록하려면 `OrderTelemetryService`을 구현하세요.
- [ ] 낮은 카디널리티 차원만 사용하여 CloudWatch `MetricDatum`을 빌드합니다.
      `Outcome`, `Service`, `Source`.
- [ ] Build CloudWatch 정리된 필드를 사용하여 이벤트를 기록합니다.
      이벤트 ID, 결과, 서비스, 소스, 경과 시간 및 안전 오류 요약.
- [ ] 자격 증명, 토큰, 헤더, 환경 값을 기록하거나 반환하지 마십시오.
      전체 예외 스택, 원시 메타데이터 문서 또는 높은 카디널리티 ID
      CloudWatch 크기.
- [ ] 다음을 통해 선택한 Micrometer 미터 스냅샷을 게시합니다.
      `CloudWatchMeterPublishingOperations`.
- [ ] 요청 또는 속성이 선택된 경우에만 명시적 메타데이터 읽기를 구현합니다.
      안에; 그렇지 않으면 메타데이터를 건너뛴 것으로 보고합니다.
- [ ] `OrderTelemetryController` 엔드포인트를 추가합니다.
      `POST /api/aws-observability/orders` 그리고
      `GET /api/aws-observability/metadata`.
- [ ] 성공, 실패, 건너뛴 메타데이터, 메타데이터에 대한 컨트롤러 테스트 추가
      활성화, 유효성 검사 오류 및 로컬 프로필 연결.
- [ ] `./gradlew :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`를 실행하세요.

### 작업 6: README 및 다이어그램

- [ ] `aws/cloudwatch-imds-observability/README.md` 및 `README.ko.md`을 작성합니다.
      소스와 동등한 학습자 흐름:
      로컬 모드, 실행 명령, 엔드포인트 예, 성공 및 실패 보고서
      예, 선택적 실제 AWS 프로필, 필수 환경 변수,
      cost/cleanup 경고 및 IMDS 자격 증명 경계입니다.
- [ ] 새 예제를 포함하도록 `aws/README.md` 및 `aws/README.ko.md`을 업데이트하세요.
- [ ] 루트 `README.md` 및 `README.ko.md` 모듈 테이블을 업데이트합니다.
- [ ] 계층화된 아키텍처 다이어그램 만들기:
      `docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg/png`.
- [ ] 모범 사례 시퀀스 다이어그램 만들기:
      `docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg/png`.
- [ ] 공유 위키의 공식 AWS CloudWatch 및 CloudWatch 로그 아이콘을 사용하세요.
      실제 AWS 관리 서비스 노드에서만 카탈로그를 작성하세요.
- [ ] 로컬 가짜 작업 Bean과 실제 AWS 관리되는 작업 Bean을 시각적으로 구별합니다.
      서비스.
- [ ] 시퀀스 다이어그램 요구 사항:
      번호가 매겨진 통화 라벨, 눈에 보이는 라벨, 투명한 `alt`/`else` 본체,
      분기별 선 색상, 텍스트 중복 없음, 읽기 가능한 전체 크기 PNG.
- [ ] README language/parity 스크립트와 적용 가능한 모든 스크립트를 실행합니다.
      `$bluetape4k-diagram` checklist/audit.
- [ ] 만진 모든 PNG을 전체 크기로 검사하고 시각적 증거를 기록하세요.

### 작업 7: CI 및 연기 등록

- [ ] 다음에 대한 `.github/workflows/Examples.yml` 경로 필터를 추가합니다.
      `push` 및 `pull_request` 아래 `aws/cloudwatch-imds-observability/**`.
- [ ] H2/default 연기 작업에 `:aws-cloudwatch-imds-observability:test`을 추가합니다.
- [ ] 다음에 대한 테스트 결과 아티팩트 경로를 추가합니다.
      `aws/cloudwatch-imds-observability/build/test-results/test/*.xml` 그리고
      `aws/cloudwatch-imds-observability/build/reports/tests/test/`.
- [ ] `:aws-cloudwatch-imds-observability:test` 추가
      `scripts/smoke-validate.sh all-smoke`.
- [ ] `observability` 그룹에 동일한 모듈을 추가합니다.
      자격 증명이 없고 컨테이너가 없습니다.
- [ ] 오래된 확인 예상 프로젝트 수를 `88`에서 `89`로 늘립니다.
- [ ] `actionlint .github/workflows/Examples.yml`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh stale-check`를 실행하세요.

### 작업 8: 검증 및 검토

- [ ] `./gradlew :aws-cloudwatch-imds-observability:compileKotlin --warning-mode all --console=plain`를 실행하세요.
- [ ] `./gradlew :aws-cloudwatch-imds-observability:compileTestKotlin --warning-mode all --console=plain`를 실행하세요.
- [ ] `./gradlew :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`를 실행하세요.
- [ ] `./gradlew projects --console=plain`를 실행하세요.
- [ ] `node scripts/validate-readme-language.mjs`를 실행하세요.
- [ ] `node scripts/validate-readme-parity.mjs`를 실행하세요.
- [ ] `node scripts/validate-readme-architecture-diagrams.mjs`를 실행하세요.
- [ ] `node scripts/validate-sequence-diagrams.mjs`를 실행하세요.
- [ ] `$bluetape4k-diagram` 형상, 엔드포인트, 커넥터 및 시퀀스 실행
      새로운 SVG에 대한 스타일 감사.
- [ ] repo 승인 렌더러를 사용하여 SVG을 PNG로 렌더링하고 각 PNG를 검사합니다.
      전체 크기.
- [ ] `actionlint .github/workflows/Examples.yml`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh stale-check`를 실행하세요.
- [ ] `git diff --check`를 실행하세요.
- [ ] 6-R단계 구현 검토를 실행하고 P0/P1마다 수정합니다.
- [ ] `docs/lessons/` 아래에 짧은 강의를 추가합니다.

### 작업 9: PR 및 CI

- [ ] Lore 프로토콜 예고편으로 커밋하세요.
- [ ] 기능 분기를 푸시합니다.
- [ ] `debop`에 할당된 `develop`에 대해 PR를 생성합니다.
- [ ] 이슈 #317 마일스톤과 라벨을 PR에 복사하세요.
- [ ] PR 본문에 이슈 링크, 요약, 확인 증거가 포함되어 있는지 확인합니다.
      그리고 마지막 `## DoD Status` 섹션.
- [ ] `gh pr view --json assignees,labels,milestone,body`으로 라이브 PR 메타데이터를 확인합니다.
- [ ] 필수 점검 사항을 모니터링합니다. 병합을 요청하기 전에 오류를 수정하세요.

## 자체 검토

- 사양 범위: 모든 이슈 승인 기준은 작업 1-9에 매핑됩니다.
- 2단계-R P2 이월: 부분 실패, 취소, IMDS 자격 증명 없음
  문서 읽기, 해결된 API 확인, 정확한 CI/smoke 편집 및 다이어그램
  checklist/visual 검사는 명시적인 작업입니다.
- 주문: 생산 전 dependency/API 가드 및 TDD 레드 테스트
  구현.
- 공개 문서: README 로케일 쌍, AWS README 로케일 쌍 및 루트 README
  로캘 쌍이 포함되어 있습니다.
- 기본 테스트 경계: 실제 AWS 없음, EC2 런타임 없음, LocalStack 없음, 없음
  Testcontainers 기본 확인에서.
- 자리 표시자 검사 대상: 해결되지 않은 자리 표시자 마커가 없거나 제한되지 않음
  "나중" 작업은 3-R단계 종료 전에 남아 있어야 합니다.
