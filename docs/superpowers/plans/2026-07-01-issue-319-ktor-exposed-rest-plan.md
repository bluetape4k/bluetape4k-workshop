# Ktor Exposed REST 워크숍 실시 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** PostgreSQL 지원 Ktor + Exposed REST 예제를 추가하여 보여줍니다.
`bluetape4k-exposed-ktor` 트랜잭션, 오류 매핑, 학습자 친화적
경로 문서.

**사양:** `docs/superpowers/specs/2026-07-01-issue-319-ktor-exposed-rest-design.md`

**아키텍처:** Ktor 경로는 HTTP 및 유효성 검사만 소유합니다. `KtorExposedRestResources`
Hikari, Exposed `Database` 및 차단 디스패처를 소유하고 있습니다. 각 경로가 들어갑니다.
Exposed부터 `ApplicationCall.exposedJdbcTransaction(...)`까지. 테스트 공급
PostgreSQL부터 `PostgreSQLServer.Launcher.postgres`까지.

**기술 스택:** Kotlin, Ktor server/test 호스트, kotlinx 직렬화, Exposed
JDBC, HikariCP, PostgreSQL JDBC 드라이버, bluetape4k-ktor-core,
bluetape4k-exposed-ktor, bluetape4k-testcontainers, JUnit 5, bluetape4k
검증, CairoSVG렌더링된 README 다이어그램.

---

## 파일 구조

- `ktor/exposed-rest/build.gradle.kts` 생성
- `ktor/exposed-rest/src/main/kotlin/io/bluetape4k/workshop/ktor/exposedrest/*` 생성
- `ktor/exposed-rest/src/main/resources/logback.xml` 생성
- `ktor/exposed-rest/src/test/kotlin/io/bluetape4k/workshop/ktor/exposedrest/*` 생성
- `ktor/exposed-rest/src/test/resources/junit-platform.properties` 생성
- `ktor/exposed-rest/src/test/resources/logback-test.xml` 생성
- `ktor/exposed-rest/README.md` 생성
- `ktor/exposed-rest/README.ko.md` 생성
- `gradle/libs.versions.toml` 수정
- 루트 `README.md` 수정
- 루트 `README.ko.md` 수정
- `ktor/README.md` 및 `ktor/README.ko.md`이 있으면 수정하세요.
- `.github/workflows/Examples.yml` 수정
- `scripts/smoke-validate.sh` 수정
- `docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.svg/png` 생성
- `docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.svg/png` 생성
- `docs/review/2026-07-01-issue-319-implementation-review.md` 생성
- `docs/lessons/2026-07-01-issue-319-ktor-exposed-rest.md` 생성

## 종속성 및 API 가드

- [ ] 다음에 대한 카탈로그 별칭 추가:
      `bluetape4k-ktor-core`, `bluetape4k-ktor-testing`, `exposed-ktor`.
- [ ] 루트 BOM 아래의 모든 bluetape4k 별칭을 버전 없이 유지합니다.
- [ ] 기존 Ktor BOM 및 Exposed/Hikari/PostgreSQL 별칭을 사용합니다.
- [ ] 다음에 대해 컴파일을 확인하십시오.
      `installBluetape4kExposedKtor`,
      `StatusPagesConfig.bluetape4kExposedErrors`,
      `ApplicationCall.exposedJdbcTransaction` 그리고
      `PostgreSQLServer.Launcher.postgres`.

## 작업 1: 모듈 뼈대

**복잡성:** 중간

**파일:**
- `ktor/exposed-rest/build.gradle.kts` 생성
- `ktor/exposed-rest/src/main/resources/` 아래에 리소스 파일을 생성하고
  `src/test/resources/`
- `gradle/libs.versions.toml` 수정

- [ ] Ktor 서버, Ktor 테스트 호스트, kotlinx를 사용하여 Gradle 빌드를 생성합니다.
      직렬화, Exposed JDBC, HikariCP, PostgreSQL 드라이버,
      bluetape4k-ktor-코어, bluetape4k-exposed-ktor, bluetape4k-testcontainers,
      및 bluetape4k 어설션.
- [ ] `application` 메인 클래스 구성을 추가합니다.
- [ ] 인접 모듈과 일치하는 JUnit/logback 테스트 리소스를 추가합니다.
- [ ] `./gradlew projects --console=plain`을 실행하고 확인합니다.
      `:ktor-exposed-rest`.
- [ ] `./gradlew :ktor-exposed-rest:compileKotlin --warning-mode all --console=plain`를 실행하세요.

## 작업 2: TDD 빨간색 테스트

**복잡성:** 높음

**파일:**
- 테스트 생성
  `ktor/exposed-rest/src/test/kotlin/io/bluetape4k/workshop/ktor/exposedrest/`

- [ ] 에서 지원하는 create/list/read/update/delete 경로에 대해 실패한 테스트를 추가합니다.
      PostgreSQL.
- [ ] 롤백에 삽입된 행이 없음을 증명하는 실패한 테스트를 추가합니다.
- [ ] Exposed 거래 오류가 안전한 것으로 매핑되었음을 증명하는 실패한 테스트를 추가합니다.
      응답.
- [ ] 직접적인 SQL 실패가 삭제되었으며 실패했음을 입증하는 실패 테스트를 추가합니다.
      JDBC URL, 사용자 이름 또는 비밀번호를 유출합니다.
- [ ] Exposed 준비 경로에 대해 실패한 테스트를 추가합니다.
- [ ] 취소가 전파되는 대신 전파된다는 것을 증명하는 실패한 테스트를 추가합니다.
      Ktor 테스트 호스트에 노출되면 데이터베이스 오류로 변환됩니다.
- [ ] 달리다
      `./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1`
      구현하기 전에 예상되는 빨간색 오류를 기록합니다.

## 작업 3: 적용 및 지속성

**복잡성:** 높음

**파일:**
- `KtorExposedRestApplication.kt` 생성
- `KtorExposedRestResources.kt` 생성
- `BookModels.kt` 생성
- `BookRepository.kt` 생성
- `BookRoutes.kt` 생성

- [ ] 다음을 사용하여 직렬화 가능한 request/response/error DTO를 구현합니다.
      `serialVersionUID`.
- [ ] Exposed JDBC 및 간단한 PostgreSQL를 사용하여 `BookRepository`을 구현합니다.
      테이블.
- [ ] 명시적인 JDBC URL, 사용자 이름, 비밀번호로부터 리소스 생성을 구현합니다.
      그리고 드라이버 클래스.
- [ ] 결정론적 워크숍 테스트를 위해 리소스 생성 시 스키마를 재설정합니다.
- [ ] Ktor 애플리케이션이 중지되면 히카리와 디스패처를 닫습니다.
- [ ] Ktor 콘텐츠 협상 및 `StatusPages`을 설치합니다.
- [ ] `bluetape4kExposedErrors()` 및 노출된 상태 경로를 설치합니다.
- [ ] CRUD 경로를 구현합니다.
- [ ] 롤백 구현, 직접 SQL 실패 및 취소 데모 구현
      경로.
- [ ] 녹색이 될 때까지 집중적인 모듈 테스트를 순차적으로 실행합니다.

## 작업 4: README 및 다이어그램

**복잡성:** 높음

**파일:**
- `ktor/exposed-rest/README.md` 생성
- `ktor/exposed-rest/README.ko.md` 생성
- root/Ktor README 로케일 쌍 수정
- `docs/images/readme-diagrams/` 아래에 SVG/PNG 다이어그램 만들기

- [ ] 개요, 아키텍처, 종속성, 경로를 포함하여 영어 README 작성
      예, PostgreSQL Testcontainers 참고, 집중 테스트 명령 및
      transaction/error 섹션.
- [ ] 자연스러운 한국어 기술로 소스에 해당하는 한국어 README 작성
      산문.
- [ ] 관련 없는 모듈 복사본을 변경하지 않고 root/Ktor 모듈 테이블을 업데이트합니다.
- [ ] 눈에 보이는 레이어 밴드를 사용하여 위에서 아래로 아키텍처 다이어그램을 생성하고
      공유 카탈로그의 공식 PostgreSQL 아이콘입니다.
- [ ] 선 위에 번호가 매겨진 라벨을 사용하여 모범 사례 시퀀스 다이어그램을 만듭니다.
      투명한 가지 몸체, 가지별 음소거 색상, 활성화 막대,
      색상이 일치하는 화살촉.
- [ ] `~/.local/bin/cairosvg <svg> -o <png> -s 2`을 사용하여 SVG을 렌더링합니다.
- [ ] 새 SVG에서 `xmllint --noout`을 실행합니다.
- [ ] 기하학을 포함한 전체 `$bluetape4k-diagram` 체크리스트를 실행하세요.
      엔드포인트, 혼합 모서리, 커넥터, 시퀀스 스타일, 마커 색상,
      해당하는 경우 라벨 오버 라인, 범례, 아이콘 및 시각적 확인이 가능합니다.
- [ ] 눈 검사 및 기록을 위해 터치된 모든 PNG을 전체 크기로 엽니다.
      증거.

## 작업 5: CI 및 연기 등록

**복잡성:** 중간

**파일:**
- `.github/workflows/Examples.yml` 수정
- `scripts/smoke-validate.sh` 수정

- [ ] 컨테이너 지원 예제 레인에 `:ktor-exposed-rest:test`을 추가합니다.
- [ ] `:ktor-exposed-rest:test`을 Docker가 없는 연기 차선에 두지 마세요.
- [ ] 새 모듈에 대한 테스트 아티팩트 업로드 경로를 추가합니다.
- [ ] 관련 유효성 검사 스크립트 그룹에 모듈을 추가합니다.
- [ ] 확인 후 오래된 확인 예상 프로젝트 수를 조정합니다.
      `./gradlew projects`.
- [ ] `actionlint .github/workflows/Examples.yml`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh stale-check`를 실행하세요.
- [ ] 편집된 컨테이너 유효성 검사 명령을 순차적으로 실행합니다.

## 작업 6: 검토, 강의 및 PR

**복잡성:** 중간

**파일:**
- `docs/review/2026-07-01-issue-319-implementation-review.md` 생성
- `docs/lessons/2026-07-01-issue-319-ktor-exposed-rest.md` 생성

- [ ] 정확성, 거래 경계,
      취소, SQL 오류 삭제, Testcontainers 수명 주기, README
      명확성 및 다이어그램 체크리스트 적용 범위.
- [ ] 구현 검토 결과 및 수정 사항을 기록합니다.
- [ ] 맥락, 결정, 결과, 검증이 포함된 짧은 강의를 녹화하세요.
      증거 및 미래 대리인 지침.
- [ ] `git diff --check`를 실행하세요.
- [ ] Lore 프로토콜로 커밋합니다.
- [ ]  #319을 해결하고 `debop`을 할당하고 이슈 마일스톤을 복사하는 PR를 생성합니다.
      그리고 라벨.
- [ ] `gh pr view --json body`으로 실제 PR 본문을 확인합니다. 마지막 `##`
      제목은 `## DoD Status`이어야 합니다.
- [ ] `gh pr view`으로 라이브 PR 메타데이터를 확인합니다.

## 최종 검증 체크리스트

- [ ] `./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1`
- [ ] `./gradlew :ktor-exposed-rest:compileKotlin --warning-mode all --console=plain`
- [ ] `./gradlew projects --console=plain`
- [ ] `./scripts/smoke-validate.sh stale-check`
- [ ] `./scripts/smoke-validate.sh data-access-full`
- [ ] `./scripts/smoke-validate.sh diagram-qa`
- [ ] `actionlint .github/workflows/Examples.yml`
- [ ] `xmllint --noout docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.svg`
- [ ] `xmllint --noout docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.svg`
- [ ] `git diff --check`

## 정지 조건

- 워크플로 게이트를 건너뛰거나 증거가 약하면 중지하고 복구합니다.
- `bluetape4k-exposed-ktor` API가 검증된 것과 다르면 중지하고 다시 디자인하세요.
  현지 소스.
- Ktor 테스트 호스트가 취소를 노출할 수 없는 경우 테스트를 중지하고 범위를 좁힙니다.
  직접 전파; 정확한 제한 사항을 문서화하고 업스트림 도우미를 유지하세요.
  transaction/error 테스트에서 다루는 동작입니다.
- 병합하기 전에 중지하십시오. PR 생성이 범위 내에 있습니다. 병합하려면 나중에 사용자가 필요합니다.
  지침.
