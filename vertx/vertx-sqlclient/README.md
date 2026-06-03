# Vert.x Sql Client Example

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Vert.x Sql Client Example** as a runnable Vert.x reactive service workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Vert.x Sql Client Example Graphviz architecture diagram](../../docs/images/readme-diagrams/vertx-vertx-sqlclient-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.vertx` as the source of truth when comparing this README with the code.

## Sequence Diagram

![Vert.x Sql Client Example sequence diagram](../../docs/images/readme-diagrams/vertx-vertx-sqlclient-sequence-01.png)

## Reactive SQL Processing Flow

![Vert.x Sql Client Example Diagram 1](../../docs/images/readme-diagrams/vertx-vertx-sqlclient-readme-sequence-01.png)

[Vert.x Sql Client](https://vertx.io/docs/vertx-sql-client/java/) and
[MyBatis Dynamic SQL](https://mybatis.org/mybatis-dynamic-sql/docs/introduction.html)
are used in this example to access a database asynchronously and non-blockingly.

This approach combines MyBatis SQL Mapper capabilities with the Vert.x Sql Client.

## Mapping with Vert.x data objects

Rows can be mapped directly to DTOs without using the Vert.x SqlClient `RowMapper`.
See the `UserDataObject` implementation in the `dataobject` folder.

1. Add `io.vertx:vertx-codegen:4.3.1:processor` as a dependency.

```
compileOnly(Libs.vertx_codegen)
kapt(Libs.vertx_codegen)
kaptTest(Libs.vertx_codegen)
```

2. Add `package-info.java` to the module.

[package-info.java](src/main/java/io/bluetape4k/workshop/sqlclient/package-info.java)

```java
@ModuleGen(name = "vertx-sqlclient-demo", groupPackage = "io.bluetape4k.workshop.sqlclient")
package io.bluetape4k.workshop.sqlclient;

import io.vertx.codegen.annotations.ModuleGen;
```

Reference:
[Mapping with Vert.x data objects](https://vertx.io/docs/vertx-sql-client-templates/java/#_mapping_with_vert_x_data_objects)

## Used bluetape4k Features

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `withSuspendTransaction { }` | `bluetape4k-vertx` | `JDBCPoolExamples.kt`, `AbstractSqlClientTest` | Opens a transaction from `Pool`, runs a suspend block, and automatically commits or rolls back |
| `testWithSuspendTransaction` | `bluetape4k-vertx` | All tests | Test helper combining `VertxTestContext` with transactions; automatic transaction rollback isolates tests |
| `tupleMapperOfRecord` | `bluetape4k-vertx` | `SqlClientTemplateExamples.kt` | Utility that converts Kotlin data classes / records into Vert.x `TupleMapper` instances |
| `MySQL8Server.Launcher` | `bluetape4k-testcontainers` | `AbstractSqlClientTest` | Testcontainers MySQL 8 singleton server; all tests in the module reuse one container |
| `runSuspendIO { }` | `bluetape4k-junit5` | All tests | Provides the `runBlocking(Dispatchers.IO)` pattern as a JUnit 5 extension |
| `KLoggingChannel` | `bluetape4k-logging` | All companion objects | Structured logging with coroutine context |
| `requireNotBlank` | `bluetape4k-core` | Input validation | Provides the `require(value.isNotBlank())` pattern as an extension function |
| `bluetape4k-assertions` | `bluetape4k-core` | All tests | Readable assertions such as `shouldBeEqualTo` and `shouldHaveSize` |

## bluetape4k Before / After

### `withSuspendTransaction { }` vs Future Chaining

```kotlin
// Before - Vert.x Future + callback chaining
pool.withTransaction { conn ->
    conn.query("SELECT * from test").execute()
        .flatMap { rows ->
            // Process result
            Future.succeededFuture(rows)
        }
}.onSuccess { result ->
    // Handle success
}.onFailure { err ->
    // Handle failure
}

// After - bluetape4k withSuspendTransaction (sequential processing with a suspend block)
pool.withSuspendTransaction { conn: SqlConnection ->
    val rows = conn.query("SELECT * from test").execute().coAwait()
    // Process result; automatically rolls back on exception
}
```

### `testWithSuspendTransaction` vs Manual Test Isolation

```kotlin
// Before - data must be cleaned up manually after the test
@Test
fun `test insert`(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
        pool.withSuspendTransaction { conn ->
            conn.query("INSERT INTO test VALUES (3, 'Test')").execute().coAwait()
            // Data remains after the test and can affect other tests
        }
        testContext.completeNow()
    }
}

// After - bluetape4k testWithSuspendTransaction (automatic rollback + testContext handling)
@Test
fun `test insert`(vertx: Vertx, testContext: VertxTestContext) = runSuspendIO {
    vertx.testWithSuspendTransaction(testContext, pool) {
        pool.query("INSERT INTO test VALUES (3, 'Test')").execute().coAwait()
        // Automatically rolls back after test completion, ensuring isolation between tests
    }
}
```

### `MySQL8Server.Launcher` Testcontainers Singleton

```kotlin
// Before - create a container for each test or class
class MyTest {
    companion object {
        @JvmField
        @Container
        val mysql = MySQLContainer("mysql:8.0")  // starts a new container each time
    }
}

// After - bluetape4k singleton launcher (reuses one container across the JVM)
abstract class AbstractSqlClientTest {
    companion object: KLoggingChannel() {
        val mysql = MySQL8Server.Launcher.mysql  // returns the already-started instance
    }
}
```

## Cancellation, Structured Concurrency, and Context Propagation

### `withSuspendTransaction { }` Cancellation and Automatic Rollback

If a coroutine is cancelled while the transaction block is running, `withSuspendTransaction` performs a rollback.
For example, even when a parent scope is cancelled by a timeout or an HTTP client disconnects,
the DB connection is returned normally to the pool after the transaction rolls back.

```kotlin
// Cancellation due to timeout -> automatic rollback
withTimeout(200) {
    pool.withSuspendTransaction { conn ->
        conn.query("INSERT INTO items VALUES (...)").execute().coAwait()
        delay(500)  // exceeds timeout -> CancellationException
        // Exception before block exit -> automatic ROLLBACK
    }
}
// INSERT does not remain in the DB
```

### `testWithSuspendTransaction` Test Isolation Guarantee

To prevent state contamination between tests, `testWithSuspendTransaction`
always rolls back regardless of whether the block completes. This guarantees an independent initial state for each test.

```kotlin
@Test
fun `insert data test`(vertx: Vertx, testContext: VertxTestContext) = runSuspendIO {
    vertx.testWithSuspendTransaction(testContext, pool) {
        val rows = pool.preparedQuery("INSERT INTO users VALUES (?, ?)")
            .execute(Tuple.of(1, "Alice"))
            .coAwait()
        rows.rowCount() shouldBeEqualTo 1
        // Automatically rolls back after the block completes -> no effect on the next test
    }
}
```

### Vert.x `Pool` Backpressure

`MySQLPool` / `JDBCPool` limits the maximum number of connections.
Coroutine `coAwait()` performs non-blocking waiting (backpressure) when no connection is available in the pool.
Because it does not block threads, a single event-loop thread can handle hundreds of concurrent requests.

## Build and Test

```bash
./gradlew :vertx-vertx-sqlclient:test
```
