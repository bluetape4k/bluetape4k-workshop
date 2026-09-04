# Ktor Exposed REST

[English](README.md) | 한국어

이 모듈은 backend-selective `bluetape4k-exposed-ktor-core`와
`bluetape4k-exposed-ktor-jdbc` 아티팩트를 사용하는 작은 Ktor REST 예제입니다.
테스트에서는 `PostgreSQLServer.Launcher.postgres`로 실제 PostgreSQL
Testcontainer를 띄우고, Ktor route handler가 Exposed JDBC 트랜잭션에 진입하는
흐름을 보여줍니다. 데이터베이스 오류가 나더라도 JDBC URL, SQL 문장, 사용자 이름,
비밀번호가 응답에 노출되지 않는지도 함께 검증합니다.

![Ktor Exposed REST architecture](../../docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.png)

## 무엇을 배우나

| 주제 | 볼 위치 | 중요한 이유 |
|---|---|---|
| Route 검증 | `BookRoutes.kt` | 요청 검증은 blocking JDBC 작업 전에 끝냅니다. |
| 트랜잭션 경계 | `call.exposedJdbcTransaction(...)` | Exposed 문장은 애플리케이션이 소유한 dispatcher에서 실행됩니다. |
| 선택형 backend 경계 | `bluetape4kExposedHealthRoutes(...)` + JDBC probe | backend-neutral core가 deadline/route 계약을, JDBC가 blocking readiness와 트랜잭션을 소유합니다. |
| 리소스 소유권 | `KtorExposedRestResources.kt` | Ktor가 Hikari, Exposed `Database`, dispatcher 생명주기를 소유합니다. |
| 안전한 실패 응답 | `StatusPages` + `bluetape4kExposedErrors()` | SQL/트랜잭션 상세 정보는 안전한 JSON 오류로 매핑됩니다. |
| PostgreSQL 테스트 | `KtorExposedRestApplicationTest.kt` | 기본 모듈 테스트는 공용 PostgreSQL Testcontainer wrapper를 사용합니다. |

## 의존성 구성

이 모듈은 루트 `bluetape4k-dependencies` BOM만 사용합니다. 핵심 consumer alias는
버전을 직접 갖지 않으며 `2.0.0` BOM에서 해석됩니다.

```kotlin
implementation(libs.bluetape4k.ktor.core)
implementation(libs.exposed.ktor.core)
implementation(libs.exposed.ktor.jdbc)
implementation(libs.exposed.jdbc)
implementation(libs.jetbrains.exposed.jdbc)
testImplementation(libs.bluetape4k.testcontainers)
```

이 애플리케이션은 선택형 R2DBC나 cache adapter에 직접 의존하지 않습니다. 해당
backend를 실제 classpath에 넣는 consumer만 `libs.exposed.ktor.r2dbc` 또는
`libs.exposed.ktor.cache`를 추가해야 합니다. 기존 consumer 호환성을 위해
`libs.exposed.ktor` aggregator도 남아 있지만, 신규 코드는 필요한 backend 표면만
선택합니다. bluetape4k 모듈 버전을 로컬 catalog에 따로 추가하지 말고, BOM을
바꿔야 한다면 루트 catalog의 BOM 라인을 갱신하세요.

## 실행 흐름

![Ktor Exposed REST sequence](../../docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.png)

애플리케이션은 `installBluetape4kKtorCore`로 JSON 처리를 설치하고, generic Ktor
오류와 Exposed 전용 오류를 하나의 `StatusPages` 블록에 합성합니다.

```kotlin
install(StatusPages) {
    bluetape4kExposedCoreErrors()
    bluetape4kExposedJdbcErrors()
    bluetape4kErrorResponses()
}

val readinessProbes = listOf(
    exposedKtorJdbcReadinessProbe(
        db = resources.jdbcDatabase,
        blockingDispatcher = resources.jdbcDispatcher,
        jdbcQueryTimeout = 2.seconds,
    ),
)

routing {
    bluetape4kExposedHealthRoutes(
        probes = readinessProbes,
        readinessProbeTimeout = 2.seconds,
    )
}
```

데이터베이스 route는 먼저 입력을 검증한 뒤 transaction helper에 진입합니다.

```kotlin
val request = call.receive<BookRequest>().validated()
val book = call.exposedJdbcTransaction(
    db = resources.jdbcDatabase,
    blockingDispatcher = resources.jdbcDispatcher,
) {
    BookRepository.create(request)
}
```

## Route

| Method | Path | 결과 |
|---|---|---|
| `POST` | `/api/books` | 책을 만들고 `201 BookResponse`를 반환합니다. |
| `GET` | `/api/books` | 생성 id 순서로 책 목록을 반환합니다. |
| `GET` | `/api/books/{id}` | 책 하나를 읽거나 안전한 404 오류를 반환합니다. |
| `PUT` | `/api/books/{id}` | 제목, 저자, ISBN을 교체합니다. |
| `DELETE` | `/api/books/{id}` | 행을 삭제하고 `204 No Content`를 반환합니다. |
| `POST` | `/api/books/rollback` | 한 트랜잭션 안에서 insert 후 실패시켜 rollback을 확인합니다. |
| `GET` | `/api/failures/sql` | `SQLException`을 던져 sanitized database error를 보여줍니다. |
| `GET` | `/healthz/exposed` | Exposed liveness route입니다. |
| `GET` | `/readyz/exposed` | Exposed JDBC readiness route입니다. |

예시 요청:

```bash
curl -s -X POST http://localhost:8080/api/books \
  -H 'content-type: application/json' \
  -d '{"title":"Ktor with Exposed","author":"Blue Tape","isbn":"978-0-001"}'
```

## 실행 방법

워크숍의 기본 경로는 테스트입니다. 테스트가 `bluetape4k-testcontainers`를 통해
PostgreSQL을 시작합니다.

```bash
./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1
```

수동 실행은 Testcontainer를 시작하지 않습니다. 이미 실행 중인 PostgreSQL을
지정해서 실행합니다.

```bash
POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/postgres \
POSTGRES_USERNAME=postgres \
POSTGRES_PASSWORD=postgres \
./gradlew :ktor-exposed-rest:run
```

## 오류 계약

- 빈 title, author, ISBN은 transaction helper에 들어가기 전에 거부합니다.
- 트랜잭션 실패는 `EXPOSED_TRANSACTION_FAILED`로 매핑됩니다.
- 직접 발생한 SQL 실패는 `EXPOSED_DATABASE_UNAVAILABLE`로 매핑됩니다.
- cancellation은 Exposed helper가 다시 던지며 Exposed database error payload로
  바뀌지 않습니다.
- core readiness는 하나의 monotonic deadline을 사용하고 backend 상세를 노출하지
  않은 채 `TIMEOUT`을 반환합니다.
- 오류 응답은 PostgreSQL JDBC URL, SQL 문장, 사용자 이름, 비밀번호를 그대로
  되돌려주지 않습니다.
