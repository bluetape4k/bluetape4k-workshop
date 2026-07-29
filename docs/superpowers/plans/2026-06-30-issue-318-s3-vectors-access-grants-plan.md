# AWS S3 벡터 및 접근 권한 부여 워크숍 실행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** S3를 가르치는 지역 우선 Spring Boot AWS 워크숍 예시 추가
벡터 검색 의도 및 S3 액세스 권한 부여 경계 없이
기본 테스트에서는 실시간 AWS 리소스가 필요합니다.

**사양:** `docs/superpowers/specs/2026-06-30-issue-318-s3-vectors-access-grants-design.md`

**아키텍처:** 모듈은 소규모 문서 검색 애플리케이션 서비스를 소유합니다.
`S3VectorsOperations` 및 `S3AccessGrantsOperations`을 사용하지만 기본값
runtime/test 연결은 결정적인 로컬 가짜 Bean을 사용합니다. 선택적 실제 AWS 사용법
수동 프로필로 문서화되어 있으며 CI 외부에 있습니다.

**기술 스택:** Kotlin, Spring Boot 4 MVC/Validation/Actuator, bluetape4k-aws,
AWS SDK v2 `s3vectors` 및 `s3control`, 코루틴, JUnit 5, MockK,
bluetape4k 검증, CairoSVG렌더링된 README 다이어그램.

---

## 파일 구조

- `aws/s3-vectors-access-grants/build.gradle.kts` 생성
- `aws/s3-vectors-access-grants/src/main/kotlin/io/bluetape4k/workshop/aws/s3vectorsaccess/*` 생성
- `aws/s3-vectors-access-grants/src/main/resources/application.yml` 생성
- `aws/s3-vectors-access-grants/src/test/kotlin/io/bluetape4k/workshop/aws/s3vectorsaccess/*` 생성
- `aws/s3-vectors-access-grants/src/test/resources/junit-platform.properties` 생성
- `aws/s3-vectors-access-grants/src/test/resources/logback-test.xml` 생성
- `aws/s3-vectors-access-grants/README.md` 생성
- `aws/s3-vectors-access-grants/README.ko.md` 생성
- `gradle/libs.versions.toml` 수정
- `aws/README.md` 수정
- `aws/README.ko.md` 수정
- 루트 `README.md` 수정
- 루트 `README.ko.md` 수정
- `.github/workflows/Examples.yml` 수정
- `scripts/smoke-validate.sh` 수정
- `docs/images/readme-diagrams/aws-s3-vectors-access-grants-readme-architecture-01.svg/png` 생성
- `docs/images/readme-diagrams/aws-s3-vectors-access-grants-readme-sequence-01.svg/png` 생성
- `docs/review/2026-06-30-issue-318-implementation-review.md` 생성
- `docs/lessons/2026-06-30-issue-318-s3-vectors-access-grants.md` 생성

## 종속성 및 API 가드

- [ ] `aws2-s3vectors-lib = { module = "software.amazon.awssdk:s3vectors" }`을 추가합니다.
- [ ] `aws2-s3control-lib = { module = "software.amazon.awssdk:s3control" }`을 추가합니다.
- [ ] 기존 AWS SDK BOM/version 줄에 두 별칭을 모두 유지합니다. 추가하지 마세요
      별도 버전.
- [ ] `libs.bluetape4k.aws`, `libs.aws2.s3vectors.lib`을 사용하고
      `libs.aws2.s3control.lib` 새 모듈에서.
- [ ] 확인된 클래스에 대해 컴파일을 확인합니다.
      `S3VectorsOperations`, `S3AccessGrantsOperations`,
      `PutVectorsRequest`, `QueryVectorsRequest`,
      `ListCallerAccessGrantsRequest` 및 `GetDataAccessRequest`.

## 작업 1: 모듈 뼈대

**복잡성:** 중간

**파일:**
- `aws/s3-vectors-access-grants/build.gradle.kts` 생성
- `aws/s3-vectors-access-grants/src/main/resources/application.yml` 생성
- `aws/s3-vectors-access-grants/src/test/resources/` 아래에 테스트 리소스 파일을 만듭니다.

- [ ] Spring Boot MVC, 검증, 액추에이터를 사용하여 Gradle 빌드를 생성합니다.
      구성 프로세서, 코루틴, `bluetape4k-aws`, `s3vectors`,
      `s3control`, Spring Boot 테스트, MockK 및 bluetape4k 어설션.
- [ ] `application.yml` 기본값을 추가합니다.
      `bluetape4k.workshop.aws.s3-vectors-access.vector-bucket-name`,
      `index-name`, `account-id`, `access-grants-location-arn`,
      `document-prefix`, `local-mode=true` 및 안전한 최대 크기.
- [ ] 다음과 일치하는 `junit-platform.properties` 및 `logback-test.xml`을 추가합니다.
      이웃 모듈.
- [ ] `./gradlew projects --console=plain`을 실행하고 확인합니다.
      `:aws-s3-vectors-access-grants`.
- [ ] `./gradlew :aws-s3-vectors-access-grants:compileKotlin --warning-mode all --console=plain`를 실행하세요.

## 작업 2: TDD 빨간색 테스트

**복잡성:** 높음

**파일:**
- `aws/s3-vectors-access-grants/src/test/kotlin/io/bluetape4k/workshop/aws/s3vectorsaccess/` 아래에 서비스 및 컨트롤러 테스트 만들기

- [ ] 문서 upsert가 예상한 것을 빌드하는 실패한 테스트를 추가합니다.
      `PutVectorsRequest` bucket/index/vector 키를 누르고 수정된 보고서를 반환합니다.
- [ ] 의미론적 쿼리가 예상한 것을 빌드하는 실패한 테스트를 추가합니다.
      `QueryVectorsRequest`은 일치하며 S3 개체 액세스를 의미하지 않습니다.
- [ ] 선택된 일치 항목 검색이 성공적인 결과로 통제되는 실패한 테스트를 추가합니다.
      URI 문서가 반환되기 전에 액세스 권한을 부여합니다.
- [ ] 호출자 부여 목록 빌드에 실패한 테스트를 추가합니다.
      `ListCallerAccessGrantsRequest` 계정 및 대상 범위 포함.
- [ ] 범위가 지정된 데이터 액세스 빌드 `GetDataAccessRequest`에 대한 실패한 테스트를 추가합니다.
      `Permission.READ`으로.
- [ ] 거부된 부여 상태가 `getDataAccess`을 건너뛰는 실패한 테스트를 추가합니다.
- [ ] S3 벡터 및 액세스 권한 부여 부분 실패에 대한 실패한 테스트를 추가합니다.
- [ ] 자격 증명 필드가 보고서에 존재하지 않음을 증명하는 실패한 테스트를 추가합니다.
      또는 직렬화된 JSON.
- [ ] 자격 증명과 유사한 필드 이름과 값이 일치하지 않음을 증명하는 실패한 테스트를 추가합니다.
      캡처된 애플리케이션 로그에 기록됩니다.
- [ ] `CancellationException`이(가) 다시 던져졌음을 증명하는 실패한 테스트를 추가합니다.
      서비스 경로를 일시 중단합니다.
- [ ] 성공, 유효성 검사 오류, 승인 거부 등에 대한 컨트롤러 테스트를 추가합니다.
      로컬 프로필 배선.
- [ ] `./gradlew :aws-s3-vectors-access-grants:test --warning-mode all --console=plain` 실행
      구현하기 전에 예상되는 빨간색 오류를 기록합니다.

## 작업 3: 도메인 모델 및 속성

**복잡성:** 중간

**파일:**
- `DocumentModels.kt` 생성
- `AwsS3VectorsAccessProperties.kt` 생성
- `DocumentAccessPolicy.kt` 생성

- [ ] 직렬화 가능한 데이터 클래스 추가: `DocumentVectorRequest`,
      `DocumentSearchRequest`, `DocumentSearchReport`, `AccessGrantReport`,
      `VectorSearchMatch`, `OperationStatus`, `DocumentAccessDecision`.
- [ ] 모든 데이터 클래스에 `serialVersionUID`을 추가합니다.
- [ ] bluetape4k `require*`를 사용하여 호출자 문자열 및 컬렉션 유효성을 검사합니다.
      도우미.
- [ ] `credentialsRedacted: Boolean`을 사용하여 명시적으로 수정된 액세스를 모델링합니다.
- [ ] 벡터 bucket/index 이름에 대한 기본값과 유효성 검사가 포함된 속성을 추가합니다.
      AWS 계정 ID, 액세스 권한 부여 위치 ARN, 문서 접두사, 최대 문서
      ID 길이, 최대 메타데이터 길이, 최대 벡터 크기 및 로컬 모드.
- [ ] 구성된 문서 ID만 허용하는 `DocumentAccessPolicy`을 추가하고
      안정적인 S3 URI + `Permission.READ`을 반환합니다.

## 작업 4: 로컬 가짜 작업 Bean

**복잡성:** 중간

**파일:**
- `LocalS3VectorsAccessConfig.kt` 생성
- `LocalS3VectorsOperations.kt` 생성
- `LocalS3AccessGrantsOperations.kt` 생성

- [ ] 벡터 upsert/query를 캡처하는 `S3VectorsOperations` 가짜 제공
      인텐트를 확인하고 결정적 일치 항목을 반환합니다.
- [ ] 호출자 승인을 나열하고 다음을 수행할 수 있는 `S3AccessGrantsOperations` 가짜를 제공하세요.
      자격 증명 자료 없이 allowed/denied 범위 액세스를 반환합니다.
- [ ] 캡처된 상태 package-private/test-visible를 유지하고 공개를 피하세요.
      생산 API 확장.
- [ ] 로컬 Bean이 AWS SDK 클라이언트 또는 자격 증명 공급자를 생성하지 않는지 확인합니다.
- [ ] 명시적인 profile/property 뒤의 선택적 real-AWS 프로필 연결을 보호하세요.
      추가되었는지 확인합니다.

## 작업 5: 서비스 및 HTTP 경계

**복잡성:** 높음

**파일:**
- `DocumentSearchService.kt` 생성
- `DocumentSearchController.kt` 생성
- `S3VectorsAccessGrantsApplication.kt` 생성

- [ ] upsert, 쿼리, 승인 목록 등에 대한 일시 중지 서비스 방법을 구현합니다.
      범위가 지정된 읽기 액세스 의도.
- [ ] 선택 일치 검색을 반환하는 별도의 서비스 경로로 구현합니다.
      액세스 권한 부여 결정이 허용된 후에만 문서 URI.
- [ ] 광범위한 예외 처리 전에 `CancellationException`을 다시 발생시킵니다.
- [ ] AWS SDK request/response 모델을 학습자 친화적인 보고서에 매핑합니다.
- [ ] 보고서 및 로그에서 모든 액세스 자격 증명 자료를 수정합니다.
- [ ] `POST /api/aws/s3-vectors/documents`을 추가합니다.
- [ ] `POST /api/aws/s3-vectors/query`을 추가합니다.
- [ ] `GET /api/aws/access-grants`을 추가합니다.
- [ ] `POST /api/aws/access-grants/data-access`을 추가합니다.
- [ ] `./gradlew :aws-s3-vectors-access-grants:test --warning-mode all --console=plain`를 실행하세요.

## 작업 6: README 및 다이어그램

**복잡성:** 높음

**파일:**
- `aws/s3-vectors-access-grants/README.md` 생성
- `aws/s3-vectors-access-grants/README.ko.md` 생성
- 루트 및 AWS README 로케일 쌍 수정
- `docs/images/readme-diagrams/` 아래에 SVG/PNG 다이어그램 만들기

- [ ] 개요, 실행 명령, 엔드포인트 예시와 함께 영어 README를 작성하세요.
      가짜 로컬 모드, 선택적인 실제-AWS 전제 조건, IAM/cost 정리 참고 사항,
      기존 S3 예시와의 차이점.
- [ ] 자연스러운 한국어 전문 산문으로 소스에 맞는 한국어 README를 작성해 보세요.
- [ ] README 예시와 다이어그램에 임시 액세스 키가 포함되어 있지 않은지 확인합니다.
      비밀 키, 세션 토큰, 자격 증명 JSON 또는 자격 증명 필드 이름입니다.
- [ ] AWS README 로케일 테이블 및 루트 README 로케일 모듈 테이블을 업데이트합니다.
- [ ] 현재 모범 사례를 사용하여 계층화된 아키텍처 다이어그램 만들기
      실제 AWS 서비스에 대해서만 아키텍처 제품군 및 공식 AWS 아이콘이 표시됩니다.
- [ ] 번호가 매겨진 라벨을 사용하여 모범 사례 시퀀스 다이어그램을 만듭니다.
      투명한 `alt`/`else` 몸체, 가지별 차분한 색상,
      활성화 막대 및 색상이 일치하는 화살촉.
- [ ] `~/.local/bin/cairosvg <svg> -o <png> -s 2`을 사용하여 각 SVG을 렌더링합니다.
- [ ] 새 SVG에서 `xmllint --noout`을 실행합니다.
- [ ] `$bluetape4k-diagram` 형상, 엔드포인트, 혼합 코너, 커넥터 실행
      시퀀스 스타일, 마커 색상, 레이블 오버 라인, 범례, 아이콘 및 시각적 개체
      해당되는지 확인합니다.
- [ ] 눈 검사를 위해 터치된 모든 PNG을 전체 크기로 엽니다.

## 작업 7: CI 및 연기 등록

**복잡성:** 중간

**파일:**
- `.github/workflows/Examples.yml` 수정
- `scripts/smoke-validate.sh` 수정

- [ ] 푸시 및 PR의 `aws/s3-vectors-access-grants/**`에 대한 경로 필터를 추가합니다.
- [ ] 자격 증명이 없는 AWS/example에 `:aws-s3-vectors-access-grants:test` 추가
      연기 범위.
- [ ] 새 모듈에 대한 테스트 결과 아티팩트 경로를 추가합니다.
- [ ] `scripts/smoke-validate.sh all-smoke`에 모듈을 추가하고 관련
      AWS/storage 그룹.
- [ ] 확인 후 오래된 확인 예상 프로젝트 수를 조정합니다.
      `./gradlew projects`.
- [ ] `actionlint .github/workflows/Examples.yml`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh stale-check`를 실행하세요.
- [ ] 편집된 연기 레인 명령 또는 새 항목을 포함하는 스크립트 그룹을 실행합니다.
      모듈을 만들고 `:aws-s3-vectors-access-grants:test`이 다음과 같다는 증거를 기록합니다.
      CI/Nightly 연기 검증에 사용되는 것과 동일한 경로에 포함됩니다.

## 작업 8: 검증 및 검토

**복잡성:** 높음

**파일:**
- `docs/review/2026-06-30-issue-318-implementation-review.md` 생성
- `docs/lessons/2026-06-30-issue-318-s3-vectors-access-grants.md` 생성

- [ ] `./gradlew :aws-s3-vectors-access-grants:compileKotlin --warning-mode all --console=plain`를 실행하세요.
- [ ] `./gradlew :aws-s3-vectors-access-grants:compileTestKotlin --warning-mode all --console=plain`를 실행하세요.
- [ ] `./gradlew :aws-s3-vectors-access-grants:test --warning-mode all --console=plain`를 실행하세요.
- [ ] `./gradlew projects --console=plain`를 실행하세요.
- [ ] `node scripts/validate-readme-language.mjs`를 실행하세요.
- [ ] `node scripts/validate-readme-parity.mjs`를 실행하세요.
- [ ] `node scripts/validate-readme-architecture-diagrams.mjs`를 실행하세요.
- [ ] `node scripts/validate-sequence-diagrams.mjs`를 실행하세요.
- [ ] `actionlint .github/workflows/Examples.yml`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh stale-check`를 실행하세요.
- [ ] 다음을 포함하는 대상 연기 검증 그룹을 실행하십시오.
      `:aws-s3-vectors-access-grants:test`.
- [ ] `rg -n "accessKey|secretKey|sessionToken|credentials|AccessKeyId|SecretAccessKey|SessionToken" aws/s3-vectors-access-grants README.md README.ko.md docs/images/readme-diagrams`을 실행하고 자격 증명 자료가 문서화되거나 도표화되어 있지 않은지 확인하십시오.
- [ ] `git diff --check`를 실행하세요.
- [ ] 6-R단계 7계층 구현 검토를 실행하고 P0=0/P1=0을 기록합니다.
- [ ] PR 생성 전에 강의를 추가하고 커밋하세요.

## 작업 9: PR 및 CI

**복잡성:** 중간

**파일:**
- `bluetape4k-workflow/templates/pr-body-step-dod.md`에서 생성된 PR 본문

- [ ] Lore 프로토콜 예고편으로 커밋하세요.
- [ ] `feat/issue-318-s3-vectors-access-grants`을 누르세요.
- [ ] `debop`에 할당된 `develop`에 대해 PR를 생성합니다.
- [ ] 이슈 #318 마일스톤과 라벨을 PR에 복사하세요.
- [ ] 마지막 PR 본문 섹션이 `## DoD Status`인지 확인합니다.
- [ ] 라이브 PR 메타데이터를 확인합니다.
      `gh pr view <number> --json assignees,labels,milestone,body`.
- [ ] 병합을 요청하기 전에 필수 검사를 모니터링하고 오류를 수정하세요.

## 자체 검토

- 사양 범위: 작업 1-9는 모든 #318 승인 기준에 매핑됩니다.
- API 증거: 계획은 실제 `bluetape4k-aws` 외관 이름과 AWS SDK v2를 사용합니다.
  서비스 패키지.
- 주문: dependency/API 가드 및 TDD 레드 테스트가 구현에 앞서 수행됩니다.
- 기본 테스트 경계: 라이브 AWS 없음, 자격 증명, LocalStack 또는
  Testcontainers이 필요합니다.
- 문서: 모듈 README 쌍, AWS README 쌍, 루트 README 쌍 및
  다이어그램 자산은 명시적입니다.
- CI/module 적용 범위: `./gradlew projects`, 예제 워크플로, 연기 스크립트,
  및 stale-check는 명시적입니다.
- 보안: 액세스 권한 부여 자격 증명은 수정되고 파괴적입니다. S3 제어
  작업은 범위를 벗어납니다.
