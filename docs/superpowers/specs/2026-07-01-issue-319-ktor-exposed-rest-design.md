# Issue #319 - Ktor Exposed REST 워크샵 사양

- 날짜: 2026-07-01
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/319
- 작업 유형: A형 전체 기능
- 대상 저장소: `bluetape4k/bluetape4k-workshop`
- 대상 모듈: `ktor/exposed-rest`
- Gradle 프로젝트: `:ktor-exposed-rest`

## 문제

`bluetape4k-dependencies 1.3.1`에는 게시된 내용이 포함됩니다.
`bluetape4k-exposed-ktor` 통합, 그러나 `bluetape4k-workshop`은 아직 통합되지 않음
Ktor 애플리케이션이 해당 도우미를 통해 Exposed 트랜잭션을 어떻게 사용해야 하는지 보여줍니다.
경계. 기존 예제는 Ktor 코루틴 기본 사항과 여러 가지 Exposed를 다룹니다.
Spring 변형이지만 학습자에게는 다음을 보여주는 작은 비Spring REST 서비스가 필요합니다.

- Ktor 라우트 핸들러는 Exposed JDBC 트랜잭션을 안전하게 호출합니다.
- `bluetape4k-exposed-ktor`에서 `StatusPages` 매핑.
- PostgreSQL 공유를 사용한 지원 통합 테스트
  내장된 데이터베이스 대신 `bluetape4k-testcontainers` 런처.
- 요청, 트랜잭션, 데이터베이스를 만드는 README 및 다이어그램
  경계가 분명하다.

## 현재 증거

- Issue #319은(는) 마일스톤 `1.3.1`에서 열려 있고 `debop`에 할당되었으며 레이블이 있습니다.
  `documentation`, `enhancement`, `difficulty:advanced`, `area:data-access`,
  그리고 `area:async-reactive`.
- `settings.gradle.kts`은 `ktor/*` 디렉토리를 `:ktor-*`로 자동 등록합니다.
  모듈이므로 `ktor/exposed-rest`은 `:ktor-exposed-rest`이 됩니다.
- `gradle/libs.versions.toml`은(는) 이미 루트를 가져옵니다.
  `bluetape4k-dependencies` BOM이지만 아직 별칭을 노출하지 않습니다.
  `bluetape4k-ktor-core`, `bluetape4k-ktor-testing` 또는
  `bluetape4k-exposed-ktor`.
- `bluetape4k-exposed-ktor`은 다음을 노출합니다.
  - `installBluetape4kExposedKtor(...)`
  - `StatusPagesConfig.bluetape4kExposedErrors()`
  - `ApplicationCall.exposedJdbcTransaction(...)`
- `bluetape4k-exposed-ktor`은 트랜잭션 실패를 안전한 Ktor 오류로 매핑합니다.
  `CancellationException`에 응답하고 다시 던집니다.
- `bluetape4k-testcontainers` 노출
  `io.bluetape4k.testcontainers.database.PostgreSQLServer.Launcher.postgres`
  재사용 가능한 `jdbcUrl`, `username`, `password` 및 `driverClassName` 포함
  접근자.
- 기존 워크샵 모듈은 `PostgreSQLServer.Launcher.postgres`를 사용합니다.
  PostgreSQL 지원 통합 테스트를 수행하고 컨테이너 지원 예제를 실행합니다.
  직렬 CI 레인.

## 제약

- 루트 `bluetape4k-dependencies` BOM만 사용하세요. bluetape4k를 고정하지 마세요
  모듈 버전.
- H2이 아닌 PostgreSQL을 사용하세요. 테스트에서는 다음을 사용해야 합니다.
  `bluetape4k-testcontainers`의 `PostgreSQLServer.Launcher.postgres`.
- 원시 `GenericContainer`을 인스턴스화하지 마십시오.
- Testcontainers이(가) 지원하는 확인 일련번호를 `--max-workers=1`에 보관하세요.
- 기본 런타임 코드는 Testcontainer를 시작하면 안 됩니다. 테스트 코드 공급
  PostgreSQL 연결 속성.
- 예제의 초점을 Ktor + Exposed JDBC로 유지하세요. 스프링을 교체하지 마십시오.
  예제 또는 모든 Exposed 백엔드를 다룹니다.
- README 작업은 `README.md` 및 `README.ko.md`의 이중 언어로 이루어집니다.
- 다이어그램 작업은 `$bluetape4k-diagram`을 사용해야 하고, SVG 및 PNG 자산을 포함해야 하며, 통과해야 합니다.
  현재 체크리스트를 확인하고 전체 크기의 육안 검사 증거를 기록합니다.
- 새 모듈 등록은 root/Ktor README 테이블을 포함해야 합니다(예: CI).
  적용 범위, 오래된 확인 프로젝트 수 및 `./gradlew projects`.

## 목표

1. `ktor/exposed-rest`을 Exposed JDBC에서 지원하는 Ktor REST 애플리케이션으로 추가합니다.
2. 경로 수준 트랜잭션 실행에는 `bluetape4k-exposed-ktor`을 사용하고
   노출된 오류 매핑.
3. 다음을 통해 통합 테스트에 PostgreSQL Testcontainers을 사용하세요.
   `PostgreSQLServer.Launcher.postgres`.
4. CRUD, 롤백, 삭제된 데이터베이스 오류 매핑, 준비 상태 및
   해당되는 경우 취소 전파.
5. 학습자가 처음부터 끝까지 읽을 수 있을 만큼 코드를 작게 유지하세요.
6. 문서 경로 예제, 종속성 참고 사항 및 집중된 Gradle 테스트 명령
   두 README 로케일 모두에서.
7. 현재를 따르는 아키텍처 및 시퀀스 다이어그램을 추가합니다.
   모범 사례 스타일.
8. 정상적인 연기를 내지 않고 CI/container 유효성 검사에 모듈을 등록합니다.
   테스트에는 Docker가 필요합니다.

## 논골

- 이 모듈에서는 H2, R2DBC, Spring Boot 또는 JPA를 사용하지 마세요.
- 예제보다 저장소 추상화 계층을 도입하지 마십시오.
  경로-거래 경계를 가르쳐야 합니다.
- Testcontainers을 프로덕션 기본 애플리케이션의 일부로 만들지 마세요.
- 라이브 클라우드, Redis, Kafka 또는 외부 서비스 종속성을 추가하지 마세요.
- hard Kover 임계값이나 관련되지 않은 CI 정책을 복원하지 마십시오.

## 접근 옵션

### 옵션 A - Ktor + Exposed JDBC + PostgreSQLServer

호출자가 소유한 Hikari/Exposed 리소스를 사용하여 Ktor 모듈을 만듭니다. 테스트 생성
`PostgreSQLServer.Launcher.postgres`의 리소스를 실제로 활용해 보세요.
PostgreSQL부터 Ktor의 테스트 호스트까지.

이익:

- 이슈 범위 및 사용자 요구사항과 정확하게 일치합니다.
- 인접 모듈과 동일한 Testcontainers 실행 프로그램 패턴을 사용합니다.
- 애플리케이션을 작게 유지하면서 현실적인 PostgreSQL 동작을 가르칩니다.
- CI Docker 사용을 컨테이너 지원 레인에 격리된 상태로 유지합니다.

소송 비용:

- 집중된 모듈 테스트 명령에는 Docker가 필요합니다.
- 테스트 설정에서는 PostgreSQL 실행 프로그램이 스키마 상태를 신중하게 재설정해야 합니다.
  공유됩니다.

### 옵션 B - H2 지원 Ktor Exposed 예

다른 워크샵에서 더 간단한 H2 패턴을 복사하고 기본 테스트를 유지합니다.
도커가 없습니다.

이익:

- 테스트 속도가 빨라지고 CI 설정이 줄어듭니다.

소송 비용:

- PostgreSQL를 사용하라는 명시적인 요구 사항에 의해 거부되었습니다.
- 트랜잭션 및 SQL 실패 경로에 대한 약한 동작을 가르칩니다.

### 옵션 C - Ktor + Exposed R2DBC

Exposed R2DBC 및 PostgreSQL을 사용하여 반응형 Ktor 예제를 빌드합니다.

이익:

- 유용한 비동기 데이터 액세스 확장입니다.

소송 비용:

- 이슈 #319보다 더 광범위합니다.
- 학습자가 보기 전에 driver/pool 및 반응형 트랜잭션 개념을 추가합니다.
  기본 Ktor/JDBC 통합.

## 결정

옵션 A를 사용하세요. 모듈은 Exposed JDBC을 사용하여 집중된 Ktor REST 서비스가 됩니다.
트랜잭션, PostgreSQL Testcontainers 통합 테스트, 이중 언어 README
파일과 두 개의 README 다이어그램.

## 건축학

### 런타임 구성요소

- `KtorExposedRestApplication`: Ktor 진입점 및 환경 속성
  수동 로컬 실행을 위한 브리지.
- `KtorExposedRestResources`: `HikariDataSource`, Exposed `Database` 소유 및
  차단 JDBC 코루틴 디스패처.
- `BookRoutes`: CRUD 및 실패 시연 경로를 정의합니다.
- `BookRepository`: 학습자를 위한 테이블 설정 및 Exposed 명령문
  `Book` 자원.
- `BookRequest`, `BookResponse`, `ErrorResponse`: 직렬화 가능한 DTO.
- `bluetape4k-exposed-ktor`: 소모품 `exposedJdbcTransaction`,
  `installBluetape4kExposedKtor` 및 안전한 `StatusPages` 매핑.
- PostgreSQL 테스트 컨테이너: 테스트를 통해서만 제공됩니다.
  `PostgreSQLServer.Launcher.postgres`.

### 데이터 Flow

1. 학습자가 Ktor HTTP 요청을 보냅니다.
2. 경로는 요청 페이로드 또는 경로 매개변수의 유효성을 검사합니다.
3. 경로는 `ApplicationCall.exposedJdbcTransaction(...)`를 호출합니다.
4. Exposed은 호출자가 소유한 Hikari 풀을 통해 PostgreSQL에 대해 SQL을 실행합니다.
5. 경로는 행을 학습자 친화적인 응답 DTO에 매핑합니다.
6. `StatusPages`은 Exposed 트랜잭션 실패 및 직접 SQL 실패를 처리합니다.
   JDBC URL이나 자격 증명을 유출하지 않고.
7. 취소 실패는 데이터베이스 오류로 변환되지 않습니다.

### 실패 처리

- 유효성 검사 실패는 Kotlin/bluetape4k `require*` 확인 및 Ktor를 사용합니다.
  `StatusPages`.
- 누락된 책은 작은 404 JSON 응답을 반환합니다.
- 롤백 경로가 트랜잭션 내부에 삽입된 후 실패하여 다음을 증명합니다.
  삽입된 행은 커밋되지 않습니다.
- 직접적인 SQL 실패 경로는 삭제된 데이터베이스 오류 매핑을 증명합니다.
- 취소 route/test는 취소가 아닌 취소가 전파되었음을 증명합니다.
  데이터베이스 응답으로 래핑됩니다.

## 테스트 전략

- TDD 레드 테스트는 제품 구현에 앞서 진행됩니다.
- `testApplication`을 Ktor의 테스트 호스트와 함께 사용하세요.
- 다음을 통해서만 PostgreSQL 시작
  `PostgreSQLServer.Launcher.postgres`.
- 반복 실행이 발생하지 않도록 리소스 생성 시 Exposed 스키마를 재설정하세요.
  결정론적.
- 씌우다:
  - create/list/read/update/delete 노선 예약,
  - 삽입된 행 이후의 롤백,
  - 위생화된 `SQLException` 매핑,
  - Exposed 준비 경로,
  - Ktor 테스트 호스트가 노출하는 취소 전파.
- 다음을 사용하여 집중 테스트를 실행하세요.
  `./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1`.

## 문서화 및 다이어그램 전략

- `README.md`에서는 영어 학습 경로, 의존성, 경로 예,
  PostgreSQL Testcontainers 요구 사항 및 집중된 Gradle 명령.
- `README.ko.md`은 영어README를 자연스러운 한국어 기술로 반영합니다.
  산문.
- 아키텍처 다이어그램은 명확한 레이어 밴드가 있는 위에서 아래로의 흐름을 사용합니다.
  클라이언트, Ktor API, Exposed 트랜잭션 경계, PostgreSQL.
- 시퀀스 다이어그램은 현재 모범 사례 팔레트 및 체크리스트를 따릅니다.
  호출 라인 위의 번호가 매겨진 라벨, 투명한 분기 본체, 활성화 바,
  음소거된 가지 색상, 해당되는 경우 둥근 팔꿈치 커넥터 및
  색상이 일치하는 화살촉.
- 다이어그램 증거에는 `diagram-qa`, SVG 검증, 렌더링된 PNG가 포함되어야 합니다.
  생성, 실물 크기 육안 검사 및 구체적인 체크리스트 수를 계산합니다.

## 확인

- `./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1`
- `./gradlew :ktor-exposed-rest:compileKotlin --warning-mode all --console=plain`
- `./gradlew projects --console=plain`
- `./scripts/smoke-validate.sh stale-check`
- `./scripts/smoke-validate.sh data-access-full`
- `./scripts/smoke-validate.sh diagram-qa`
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## 위험

- PostgreSQL Testcontainers는 H2보다 느릴 수 있습니다. 기본값에서 유지
  차선을 연기하고 연속적으로 실행하십시오.
- Ktor 테스트 호스트 취소 동작이 전파된 예외로 표면화될 수 있음
  HTTP 응답보다는; 테스트는 전파 경로를 확인해야 합니다.
  인위적인 대응을 강요하는 대신.
- 레이블이 선이나 색상을 덮는 경우 다이어그램 회귀가 발생할 수 있습니다.
  모범 사례 시퀀스 스타일; PR 전에 전체 체크리스트를 실행하세요.
