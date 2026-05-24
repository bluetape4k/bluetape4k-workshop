# Vert.x Sql Client Example

## Reactive SQL 처리 흐름

![Reactive SQL diagram](../../docs/images/readme-diagrams/vertx-vertx-sqlclient-sequence-01.png)

[Vert.x Sql Client](https://vertx.io/docs/vertx-sql-client/java/) 와
[MyBatis Dynamic SQL](https://mybatis.org/mybatis-dynamic-sql/docs/introduction.html) 을
사용하여 Async/Non-Blocking 방식으로 데이터베이스를 사용하는 예제입니다.

이 방식은 MyBatis의 SQL Mapper 기능과 Vert.x의 Sql Client를 조합하여 사용하는 방식입니다.

## Mapping with Vert.x data objects

Vert SqlClient 의 RowMapper 를 사용하지 않고, Rows 를 바로 DTO 에 매핑할 수 있다
dataobject 폴더의 UserDataObject 구현을 참고

1. `io.vertx:vertx-codegen:4.3.1:processor` 를 dependency 에 추가한다

```
compileOnly(Libs.vertx_codegen)
kapt(Libs.vertx_codegen)
kaptTest(Libs.vertx_codegen)
```

2. module에 package-info.java 를 추가한다

[package-info.java](src/main/java/io/bluetape4k/workshop/sqlclient/package-info.java)

```java
@ModuleGen(name = "vertx-sqlclient-demo", groupPackage = "io.bluetape4k.workshop.sqlclient")
package io.bluetape4k.workshop.sqlclient;

import io.vertx.codegen.annotations.ModuleGen;
```

참고 자료 :
[Mapping with Vert.x data objects](https://vertx.io/docs/vertx-sql-client-templates/java/#_mapping_with_vert_x_data_objects)

## 사용된 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `withSuspendTransaction { }` | `bluetape4k-vertx` | `JDBCPoolExamples.kt`, `AbstractSqlClientTest` | `Pool`에서 트랜잭션을 열고 suspend 블록 실행 후 자동 커밋/롤백 |
| `testWithSuspendTransaction` | `bluetape4k-vertx` | 테스트 전체 | `VertxTestContext`와 트랜잭션을 결합한 테스트 헬퍼 — 트랜잭션 자동 롤백으로 테스트 격리 |
| `tupleMapperOfRecord` | `bluetape4k-vertx` | `SqlClientTemplateExamples.kt` | Kotlin data class / record를 Vert.x `TupleMapper`로 변환하는 유틸 |
| `MySQL8Server.Launcher` | `bluetape4k-testcontainers` | `AbstractSqlClientTest` | Testcontainers MySQL 8 싱글톤 서버 — 모듈 내 모든 테스트가 하나의 컨테이너 재사용 |
| `runSuspendIO { }` | `bluetape4k-junit5` | 테스트 전체 | `runBlocking(Dispatchers.IO)` 패턴을 JUnit 5 확장으로 제공 |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | 코루틴 컨텍스트 포함 구조적 로깅 |
| `requireNotBlank` | `bluetape4k-core` | 입력 검증 | `require(value.isNotBlank())` 패턴을 확장 함수로 제공 |
| `bluetape4k-assertions` | `bluetape4k-core` | 테스트 전체 | 가독성 높은 단언문 (`shouldBeEqualTo`, `shouldHaveSize` 등) |

## bluetape4k Before / After

### `withSuspendTransaction { }` vs Future 체이닝

```kotlin
// Before — Vert.x Future + 콜백 체이닝
pool.withTransaction { conn ->
    conn.query("SELECT * from test").execute()
        .flatMap { rows ->
            // 결과 처리
            Future.succeededFuture(rows)
        }
}.onSuccess { result ->
    // 성공 처리
}.onFailure { err ->
    // 실패 처리
}

// After — bluetape4k withSuspendTransaction (suspend 블록으로 순차 처리)
pool.withSuspendTransaction { conn: SqlConnection ->
    val rows = conn.query("SELECT * from test").execute().coAwait()
    // 결과 처리 — 예외 발생 시 자동 롤백
}
```

### `testWithSuspendTransaction` vs 수동 테스트 격리

```kotlin
// Before — 테스트 후 수동으로 데이터 정리 필요
@Test
fun `test insert`(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
        pool.withSuspendTransaction { conn ->
            conn.query("INSERT INTO test VALUES (3, 'Test')").execute().coAwait()
            // 테스트 종료 후 데이터가 남아 다른 테스트에 영향
        }
        testContext.completeNow()
    }
}

// After — bluetape4k testWithSuspendTransaction (자동 롤백 + testContext 처리)
@Test
fun `test insert`(vertx: Vertx, testContext: VertxTestContext) = runSuspendIO {
    vertx.testWithSuspendTransaction(testContext, pool) {
        pool.query("INSERT INTO test VALUES (3, 'Test')").execute().coAwait()
        // 테스트 완료 후 자동 롤백 → 테스트 간 격리 보장
    }
}
```

### `MySQL8Server.Launcher` Testcontainers 싱글톤

```kotlin
// Before — 테스트마다 또는 클래스마다 컨테이너 생성
class MyTest {
    companion object {
        @JvmField
        @Container
        val mysql = MySQLContainer("mysql:8.0")  // 매번 새 컨테이너 기동
    }
}

// After — bluetape4k 싱글톤 런처 (JVM 전체에서 하나의 컨테이너 재사용)
abstract class AbstractSqlClientTest {
    companion object: KLoggingChannel() {
        val mysql = MySQL8Server.Launcher.mysql  // 이미 기동된 인스턴스 반환
    }
}
```
