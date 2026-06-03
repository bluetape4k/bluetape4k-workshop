# mongodb-coroutine demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **mongodb-coroutine demo** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![mongodb-coroutine demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springdata` as the source of truth when comparing this README with the code.

![mongodb-coroutine demo architecture diagram](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `spring-data-mongodb-coroutines`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![mongodb-coroutine demo sequence diagram](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-sequence-01.png)

## Architecture Diagram

![mongodb coroutines Class Structure diagram](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-diagram-01.png)

This example performs MongoDB work with `Spring Data Mongo` and Kotlin Coroutines.

## References

* [Spring Data MongoDB - Kotlin examples](https://github.com/spring-projects/spring-data-examples/tree/main/mongodb/kotlin)

* [Spring Data MongoDB - Reactive examples](https://github.com/spring-projects/spring-data-examples/tree/main/mongodb/reactive)

## Processing Flow

![mongodb-coroutine demo Diagram 1](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-readme-sequence-01.png)

## Explanation

### Value defaulting on entity construction

Kotlin allows defaulting for constructor- and method arguments.
Defaulting allows usage of substitute values if a field in the document is absent or simply `null`.
Spring Data inspects objects whether they are Kotlin types and uses the appropriate constructor.

```kotlin
data class Person(@Id val id: String?, val firstname: String? = "Walter", val lastname: String)

operations.insert<Document>().inCollection("person").one(Document("lastname", "White"))

val walter = operations.findOne<Document>(query(where("lastname").isEqualTo("White")), "person")

assertThat(walter.firstname).isEqualTo("Walter")
```

### Kotlin Extensions

Spring Data exposes methods accepting a target type to either query for or to project results values on.
Kotlin represents classes with its own type, `KClass` which can be an obstacle when attempting to obtain a Java `Class` type.

Spring Data ships with extensions that add overloads for methods accepting a type parameter by either leveraging generics or accepting `KClass` directly.

```kotlin
operations.getCollectionName<Person>()
operations.getCollectionName(Person::class)
```

### Nullability

Declaring repository interfaces using Kotlin allows expressing nullability constraints on arguments and return types.
Spring Data evaluates nullability of arguments and return types and reacts to these. Passing `null` to a non-nullable argument raises an `IllegalArgumentException`. Spring Data helps you also to prevent `null` in query results. If you wish to return a nullable result, use Kotlin's nullability marker `?`.

```kotlin
interface PersonRepository: CrudRepository<Person, String> {
    fun findOneOrNoneByFirstname(firstname: String): Person?
    fun findNullableByFirstname(firstname: String?): Person?
    fun findOneByFirstname(firstname: String): Person
}
```

### Type-Safe Kotlin Mongo Query DSL

Using the `Criteria` extensions allows to write type-safe queries via an idiomatic API.

```kotlin
operations.find<Person>(Query(Person::firstname isEqualTo "Tyrion"))
```

### Coroutines and Flow support

```kotlin
// Fetch a single result with a suspend function
val person = operations.findAll<Person>().awaitSingle()

// Stream results with Flow (backpressure support)
val persons = operations.findAll<Person>().asFlow().toList()
```

## Used bluetape4k Features

| Feature | Artifact | Code location | Benefit |
|---|---|---|---|
| `MongoDBServer.Launcher.mongoDB` | `bluetape4k-testcontainers` | `MongoClientConfig.kt`, `ReactiveMongoConfig.kt` | Testcontainers MongoDB singleton — starts one container for the entire JVM |
| `KLoggingChannel` | `bluetape4k-logging` | All companion objects | Structured logging that includes coroutine context |
| `runSuspendIO { }` | `bluetape4k-junit5` | `FlowAndCoroutineTest` | Runs suspend tests on `Dispatchers.IO` — coroutine tests without IO blocking |
| `Flow.log("label")` | `bluetape4k-coroutines` | `FlowAndCoroutineTest` | Emits structured logs for each Flow element — simplifies debugging and tracing |
| `Fakers.faker` | `bluetape4k-junit5` | `AbstractMongodbTest` | Test data generator — provides a reusable fake instance |
| `shouldBeEqualTo` | `bluetape4k-core` | All tests | Readable assertions |

## bluetape4k Before / After

### `MongoDBServer.Launcher` vs Manual Container Creation

```kotlin
// Before — start a new MongoDBContainer for every test class
@Testcontainers
class MyTest {
    companion object {
        @Container
        val mongo = MongoDBContainer("mongo:6.0")  // new container every time
    }
}

// After — bluetape4k singleton launcher (reuse one container for the entire JVM)
class MongoClientConfig: AbstractMongoClientConfiguration() {
    override fun configureClientSettings(builder: MongoClientSettings.Builder) {
        builder.applyConnectionString(
            ConnectionString(MongoDBServer.Launcher.mongoDB.connectionString)
        )  // returns the already-started instance — no container restart
    }
}
```

### `Flow.log("label")` vs Manual Logging

```kotlin
// Before — use onEach and manual log calls to log each element
val persons = operations.findAll<Person>()
    .asFlow()
    .onEach { log.debug { "person=$it" } }
    .toList()

// After — bluetape4k Flow.log() (automatic structured logging)
val persons = operations.findAll<Person>().asFlow()
    .log("persons")   // automatically logs each emit/complete/error event
    .toList()
```

### `runSuspendIO { }` vs runBlocking + Dispatchers.IO

```kotlin
// Before — specify the IO dispatcher and wrap the test in a blocking call
@Test
fun `find person`() {
    runBlocking(Dispatchers.IO) {
        val person = operations.insert<Person>().one(newPerson()).awaitSingle()
        // ...
    }
}

// After — bluetape4k runSuspendIO (JUnit 5 extension + built-in IO dispatcher)
@Test
fun `find person`() = runSuspendIO {
    val person = operations.insert<Person>().one(newPerson()).awaitSingle()
    // can call suspend functions directly in the test context
}
```

## Cancellation, Structured Concurrency, and Context Propagation

### `CoroutineCrudRepository` Flow and Cancellation Propagation

The `Flow<Person>` returned by `PersonCoroutineRepository.findAllByFirstname()` is
a Reactor `Flux` converted with `asFlow()`. When the coroutine scope is cancelled,
the upstream `Flux` subscription is cancelled immediately and the MongoDB cursor is released.

```kotlin
val job = launch {
    repository.findAllByFirstname("Tyrion")   // Flow<Person>
        .collect { person ->
            // when job.cancel() is called while this collect is running
            // Flow collection stops and the MongoDB cursor is released automatically
        }
}
delay(50)
job.cancel()   // cancellation propagates through Flow -> Flux -> MongoDB cursor
```

### `@Tailable` Cursors and Backpressure

`PersonCoroutineRepository.findWithTailableCursorBy()` exposes a MongoDB tailable cursor
as `Flux<Person>`. Converting it with `.asFlow()` lets you control consumption speed with
coroutine backpressure operators such as `buffer` and `conflate`.

```kotlin
repository.findWithTailableCursorBy()
    .asFlow()
    .buffer(capacity = 10)          // buffer up to 10 items
    .collect { person ->
        processSlowly(person)       // the tailable cursor waits even if consumption is slow
    }
```

## Build and Test

```bash
./gradlew :spring-data-mongodb-coroutines:test
```
