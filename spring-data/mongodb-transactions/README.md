# mongo-transactions demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **mongo-transactions demo** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![mongo-transactions demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-mongodb-transactions-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springdata` as the source of truth when comparing this README with the code.

![mongo-transactions demo architecture diagram](../../docs/images/readme-diagrams/spring-data-mongodb-transactions-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `spring-data-mongodb-transactions`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![mongo-transactions demo sequence diagram](../../docs/images/readme-diagrams/spring-data-mongodb-transactions-sequence-01.png)

## Architecture Diagram

![mongodb transactions Class Structure diagram](../../docs/images/readme-diagrams/spring-data-mongodb-transactions-diagram-01.png)

![mongodb transactions Sequence Flow 2 diagram](../../docs/images/readme-diagrams/spring-data-mongodb-transactions-sequence-01.png)

This example performs MongoDB work with `Spring Data Mongo` and Kotlin Coroutines.

## References

* [Spring Data MongoDB - Transaction sample](https://github.com/spring-projects/spring-data-examples/tree/main/mongodb/transactions/README.md)

## Processing Flow

![mongo-transactions demo Diagram 1](../../docs/images/readme-diagrams/spring-data-mongodb-transactions-readme-sequence-01.png)

## Explanation

### Running the Sample

The sample uses a MongoDB Testcontainers container.
It contains tests for synchronous, reactive, and coroutine transaction support
in the `imperative` / `reactive` / `coroutine` packages.

### Sync Transactions

`MongoTransactionManager` is the gateway to the well known Spring transaction support.
The `MongoTransactionManager` binds a `ClientSession` to the thread.

```kotlin
@Service
class TransitionService {
    @Transactional
    fun run(id: Int) {
        val process = lookup(id)
        if (process.state != State.CREATED) return
        start(process)
        verify(process)
        finish(process)
    }
}
```

### Programmatic Reactive Transactions

`ReactiveMongoTemplate` offers dedicated methods (like `inTransaction()`) for operating within a transaction.

```kotlin
@Service
class ReactiveTransitionService {
    fun run(id: Int): Mono<Int> =
        template.inTransaction().execute { action ->
            lookup(id)
                .filter { State.CREATED == it.state }
                .flatMap { process -> start(action, process) }
                .flatMap { this::verify }
                .flatMap { process -> finish(action, process) }
        }.next().map { it.id }
}
```

### Declarative Reactive Transactions

`ReactiveMongoTransactionManager` adds the `ClientSession` to
the `reactor.util.context.Context`. `ReactiveMongoTemplate` detects the session and operates
on these resources accordingly.

### Coroutine Transactions

`CoroutineManagedTransitionService` combines `@Transactional suspend fun` with
`ReactiveMongoTransactionManager`.
The `ReactorContext` from `kotlinx-coroutines-reactor` automatically connects the Reactor Context
(transaction session) with the coroutine context.

```kotlin
@Service
class CoroutineManagedTransitionService(
    private val repository: CoroutineProcessRepository,
    private val operations: ReactiveMongoOperations,
) {
    @Transactional
    suspend fun run(id: Int) {
        val process = lookup(id)
        if (process.state != State.CREATED) return
        start(process)   // update → ACTIVE
        verify(process)  // throws on id % 3 == 0 → triggers rollback
        finish(process)  // update → DONE
    }
}
```

## Used bluetape4k Features

| Feature | Artifact | Code location | Benefit |
|---|---|---|---|
| `MongoDBServer.Launcher.mongoDB` | `bluetape4k-testcontainers` | `AbstractMongodbTest` | Testcontainers MongoDB singleton — shared across the entire JVM, including the replica set |
| `KLoggingChannel` | `bluetape4k-logging` | `CoroutineManagedTransitionService`, tests | Structured logging that includes coroutine context |
| `log.debug { }` / `log.warn { }` | `bluetape4k-logging` | All tests | Lambda-based lazy log messages — no string creation for disabled levels |
| `uninitialized()` | `bluetape4k-core` | All tests | Replaces `lateinit` for `@Autowired` fields — type-safe initialization marker |
| `shouldBeEqualTo`, `shouldNotBeNull` | `bluetape4k-core` | All tests | Readable assertions |
| `Fakers.faker` | `bluetape4k-junit5` | `AbstractMongodbTest` | Test data generator — reusable fake instance |

## bluetape4k Before / After

### `MongoDBServer.Launcher` vs Manual Container Creation

```kotlin
// Before — create MongoClient directly in AbstractReactiveMongoConfiguration
class TestConfig: AbstractReactiveMongoConfiguration() {
    @Bean
    override fun reactiveMongoClient(): MongoClient {
        // manual connection string management — port collisions and cleanup responsibility
        return MongoClients.create("mongodb://localhost:27017")
    }
}

// After — bluetape4k singleton launcher (automatic cleanup)
abstract class AbstractMongodbTest {
    companion object: KLoggingChannel() {
        val mongodb by lazy { MongoDBServer.Launcher.mongoDB }
        fun createReactiveMongoClient() =
            MongoClients.create(mongodb.url)  // reuse the already-started instance
    }
}
```

### `uninitialized()` vs lateinit var

```kotlin
// Before — lateinit var (UnitializedPropertyAccessException if accessed before initialization)
@Autowired
private lateinit var managedTransitionService: CoroutineManagedTransitionService

// After — bluetape4k uninitialized() (type-safe marker with explicit intent)
@Autowired
private val managedTransitionService: CoroutineManagedTransitionService = uninitialized()
```

### `@Transactional suspend fun` vs Reactor-Chained Transactions

```kotlin
// Before — Reactor style (transaction composed with flatMap chaining)
fun run(id: Int): Mono<Int> =
    template.inTransaction().execute { action ->
        lookup(id)
            .filter { State.CREATED == it.state }
            .flatMap { start(action, it) }
            .flatMap { verify(it) }
            .flatMap { finish(action, it) }
    }.next().map { it.id }

// After — coroutine style (@Transactional suspend fun, sequential code)
@Transactional
suspend fun run(id: Int) {
    val process = lookup(id)
    if (process.state != State.CREATED) return
    start(process)    // automatic rollback when an exception occurs
    verify(process)   // id % 3 == 0 → IllegalStateException → rollback
    finish(process)
}
```

## Cancellation, Structured Concurrency, and Context Propagation

### `@Transactional` + Coroutine Context

`ReactiveMongoTransactionManager` stores the transaction session in the Reactor `Context`.
`kotlinx-coroutines-reactor` connects that context with the coroutine context through the
`ReactorContext` element, so every `awaitSingle()` / `awaitFirst()` call inside
`@Transactional suspend fun` runs in the same session.

### Coroutine Cancellation and Transaction Rollback

If a coroutine is cancelled while `@Transactional suspend fun` is running (for example, timeout
or parent-scope cancellation), the Spring AOP transaction interceptor detects
`CancellationException` and rolls back the transaction.

```kotlin
// timeout-driven cancellation -> automatic transaction rollback
withTimeout(500) {
    service.run(processId)  // CancellationException after 500ms -> rollback
}
```

### Test Verification Pattern

```kotlin
@Test
fun `coroutine transaction commit and rollback`() = runTest {
    repeat(10) {
        val process = managedTransitionService.newProcess()
        try {
            managedTransitionService.run(process.id)
            stateInDb(process) shouldBeEqualTo State.DONE       // verify commit
        } catch (e: IllegalStateException) {
            stateInDb(process) shouldBeEqualTo State.CREATED    // verify rollback
        }
    }
}
```

## Build and Test

```bash
./gradlew :spring-data-mongodb-transactions:test
```
