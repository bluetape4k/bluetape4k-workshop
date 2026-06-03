# Spring Data R2DBC Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Data R2DBC Demo** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Data R2DBC Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-examples-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springdata` as the source of truth when comparing this README with the code.

![Spring Data R2DBC Demo architecture diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-examples-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `spring-data-r2dbc-examples`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![Spring Data R2DBC Demo sequence diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-examples-sequence-01.png)

## Architecture Diagram

![r2dbc examples Class Structure diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-examples-diagram-01.png)

![r2dbc examples Sequence Flow 2 diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-examples-sequence-01.png)

## References

* [Spring Data Examples - r2dbc/example](https://github.com/spring-projects/spring-data-examples/tree/main/r2dbc/example)
* [Spring Data Examples - r2dbc/query-by-example](https://github.com/spring-projects/spring-data-examples/tree/main/r2dbc/query-by-example)

* [Spring Data R2DBC and Kotlin Coroutines](https://xebia.com/blog/spring-data-r2dbc-and-kotlin-coroutines/)
* [Kotlin + Spring Webflux + R2DBC](https://dgahn.tistory.com/8)

This project shows some sample usage of the work-in-progress R2DBC support for Spring Data.

### Interesting bits to look at

- `InfrastructureConfiguration` - sets up a R2DBC `ConnectionFactory` based on the R2DBC H2
  driver (https://github.com/r2dbc/r2dbc-h2[r2dbc-h2]), a `DatabaseClient` and a `R2dbcRepositoryFactory` to eventually
  create a `CustomerRepository`.
- `CustomerRepository` - a standard Spring Data reactive CRUD repository exposing query methods using manually defined
  queries
- `CustomerRepositoryIntegrationTests` - to initialize the database with some setup SQL and the inserting and
  reading `Customer` instances.
- `TransactionalService` - uses declarative transaction to apply a transactional boundary to repository operations.

This project contains samples of Query-by-Example of Spring Data R2DBC.

### Support for Query-by-Example

Query by Example (QBE) is a user-friendly querying technique with a simple interface.
It allows dynamic query creation and does not require to write queries containing field names.
In fact, Query by Example does not require to write queries using SQL at all.

An `Example` takes a data object (usually the entity object or a subtype of it) and a specification how to match
properties.
You can use Query by Example with Repositories.

```kotlin
interface PersonRepository:
    CoroutineCrudRepository<Person, Long>,
    CoroutineQueryByExampleExecutor<Person>

val example = Example.of(Person("", "Snow", 0))
repository.findAll(example).toList()

val matcher = ExampleMatcher.buildExampleMatcher(Person::lastname.name)
    .withMatcher(Person::lastname.name, GenericPropertyMatchers.exact())
    .withIgnoreNullValues()
val example = Example.of(Person("", "White", 0), matcher)
repository.count(example)
```

## Processing Flow

![Spring Data R2DBC Demo Diagram 1](../../docs/images/readme-diagrams/spring-data-r2dbc-examples-readme-sequence-01.png)

## Used bluetape4k Features

| Feature | Artifact | Code location | Benefit |
|---|---|---|---|
| `connectionFactoryInitializer { }` | `bluetape4k-r2dbc` | `ApplicationConfiguration.kt` | DSL for creating `ConnectionFactoryInitializer` — reduces boilerplate configuration code |
| `buildExampleMatcher(vararg props)` | `bluetape4k-spring-boot4-r2dbc` | `PersonRepositoryIntegrationTest` | QBE `ExampleMatcher` DSL — type-safe matcher construction without string property names |
| `asLong()` / `toUtf8Bytes()` | `bluetape4k-core` | `ApplicationConfiguration.kt` | Extension functions for `Row` column value conversion and string -> UTF-8 byte conversion |
| `KLoggingChannel` | `bluetape4k-logging` | All companion objects | Structured logging that includes coroutine context |
| `shouldBeEqualTo`, `shouldNotBeNull` | `bluetape4k-core` | All tests | Readable assertions such as `shouldBeEqualTo` and `shouldContainSame` |

## bluetape4k Before / After

### `connectionFactoryInitializer { }` vs Manual Bean Creation

```kotlin
// Before — standard Spring R2DBC style (write bean creation manually)
@Bean
fun initializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
    val initializer = ConnectionFactoryInitializer()
    initializer.setConnectionFactory(connectionFactory)
    val populator = ResourceDatabasePopulator()
    populator.addScript(ClassPathResource("schema.sql"))
    initializer.setDatabasePopulator(populator)
    return initializer
}

// After — bluetape4k DSL (concise lambda builder)
@Bean
fun initializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer =
    connectionFactoryInitializer(connectionFactory) {
        setDatabasePopulator(ResourceDatabasePopulator(ByteArrayResource(sql.toUtf8Bytes())))
    }
```

### `buildExampleMatcher` vs Manual ExampleMatcher Configuration

```kotlin
// Before — standard ExampleMatcher (manual string property names)
val matcher = ExampleMatcher.matching()
    .withIgnorePaths("age")
    .withMatcher("lastname", GenericPropertyMatchers.exact())
    .withIgnoreNullValues()

// After — bluetape4k buildExampleMatcher (type-safe property references)
val matcher = ExampleMatcher.buildExampleMatcher(Person::lastname.name)
    .withMatcher(Person::lastname.name, GenericPropertyMatchers.exact())
    .withIgnoreNullValues()
```

## Cancellation, Structured Concurrency, and Context Propagation

### R2DBC Flow and Coroutine Cancellation

The `Flow`-returning methods in `CoroutineCrudRepository` propagate coroutine cancellation signals
to the R2DBC publisher. If a `runTest { }` block times out or throws an exception in a test,
the subscription collecting `Flow<Customer>` is automatically cancelled and the connection is
returned to the DB connection pool.

```kotlin
// Flow cancellation propagation example
runTest {
    val job = launch {
        repository.findAll()        // Flux -> Flow conversion
            .collect { customer ->
                // when job.cancel() is called while this collect lambda is running
                // the R2DBC subscription is cancelled immediately and the connection returns to the pool
            }
    }
    delay(100)
    job.cancel()  // -> the upstream R2DBC Flux is also cancelled
}
```

### `@Transactional` + Coroutine Context Propagation

`TransactionalService` uses `@Transactional suspend fun`.
Spring R2DBC propagates transaction context through Reactor Context, and the `ReactorContext`
element from `kotlinx-coroutines-reactor` connects it with the coroutine context.

```kotlin
// TransactionalService.kt
@Transactional
suspend fun insert(customer: Customer): Customer =
    repository.save(customer)  // runs in the same transaction context
```

## Build and Test

```bash
./gradlew :spring-data-r2dbc-examples:test
```
