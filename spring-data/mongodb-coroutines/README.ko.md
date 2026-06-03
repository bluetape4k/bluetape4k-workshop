# mongodb-coroutine demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **mongodb-coroutine demo**를 실행 가능한 Spring Data 영속성 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![mongodb-coroutine demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README와 코드를 비교할 때는 `io.bluetape4k.workshop.springdata` 패키지를 기준으로 삼으세요.

![mongodb-coroutine demo architecture diagram](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-diagram-01.png)

## 흐름 다이어그램

1. `spring-data-mongodb-coroutines`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업은 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 에셋이 있는 모듈은 아래 이미지가 상호작용 순서를 보여 주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![mongodb-coroutine demo sequence diagram](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-sequence-01.png)

## 아키텍처 다이어그램

![mongodb coroutines Class Structure diagram](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-diagram-01.png)

이 예제는 `Spring Data Mongo`와 Kotlin Coroutines로 MongoDB 작업을 수행합니다.

## 참고

* [Spring Data MongoDB - Kotlin examples](https://github.com/spring-projects/spring-data-examples/tree/main/mongodb/kotlin)

* [Spring Data MongoDB - Reactive examples](https://github.com/spring-projects/spring-data-examples/tree/main/mongodb/reactive)

## 처리 흐름

![mongodb-coroutine demo Diagram 1](../../docs/images/readme-diagrams/spring-data-mongodb-coroutines-readme-sequence-01.png)

## 설명

### Entity 생성 시 기본값 적용

Kotlin은 constructor와 method argument에 기본값을 지정할 수 있습니다.
기본값을 사용하면 document의 field가 없거나 단순히 `null`인 경우 대체 값을 사용할 수 있습니다.
Spring Data는 object가 Kotlin type인지 검사하고 적절한 constructor를 사용합니다.

```kotlin
data class Person(@Id val id: String?, val firstname: String? = "Walter", val lastname: String)

operations.insert<Document>().inCollection("person").one(Document("lastname", "White"))

val walter = operations.findOne<Document>(query(where("lastname").isEqualTo("White")), "person")

assertThat(walter.firstname).isEqualTo("Walter")
```

### Kotlin Extensions

Spring Data는 조회 대상이나 결과 projection 값을 지정하기 위해 target type을 받는 method를 제공합니다.
Kotlin은 class를 자체 type인 `KClass`로 표현하므로 Java `Class` type을 얻으려 할 때 장애물이 될 수 있습니다.

Spring Data는 generic을 활용하거나 `KClass`를 직접 받아 type parameter를 받는 method에 overload를 추가하는 extension을 제공합니다.

```kotlin
operations.getCollectionName<Person>()
operations.getCollectionName(Person::class)
```

### Nullability

Kotlin으로 repository interface를 선언하면 argument와 return type의 nullability 제약을 표현할 수 있습니다.
Spring Data는 argument와 return type의 nullability를 평가하고 그에 맞게 반응합니다. non-null argument에 `null`을 전달하면 `IllegalArgumentException`이 발생합니다. Spring Data는 query result에서 `null`이 나오지 않도록 막는 데도 도움을 줍니다. nullable result를 반환하려면 Kotlin nullability marker `?`를 사용하세요.

```kotlin
interface PersonRepository: CrudRepository<Person, String> {
    fun findOneOrNoneByFirstname(firstname: String): Person?
    fun findNullableByFirstname(firstname: String?): Person?
    fun findOneByFirstname(firstname: String): Person
}
```

### Type-Safe Kotlin Mongo Query DSL

`Criteria` extension을 사용하면 idiomatic API로 type-safe query를 작성할 수 있습니다.

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

## 사용된 bluetape4k 기능

| 기능 | Artifact | 코드 위치 | 이점 |
|---|---|---|---|
| `MongoDBServer.Launcher.mongoDB` | `bluetape4k-testcontainers` | `MongoClientConfig.kt`, `ReactiveMongoConfig.kt` | Testcontainers MongoDB singleton — 전체 JVM에서 하나의 container를 시작합니다 |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | coroutine context를 포함하는 structured logging |
| `runSuspendIO { }` | `bluetape4k-junit5` | `FlowAndCoroutineTest` | `Dispatchers.IO`에서 suspend test 실행 — IO blocking 없는 coroutine test |
| `Flow.log("label")` | `bluetape4k-coroutines` | `FlowAndCoroutineTest` | 각 Flow element에 대한 structured log 출력 — debugging과 tracing 단순화 |
| `Fakers.faker` | `bluetape4k-junit5` | `AbstractMongodbTest` | Test data generator — 재사용 가능한 fake instance 제공 |
| `shouldBeEqualTo` | `bluetape4k-core` | 모든 tests | 읽기 쉬운 assertion |

## bluetape4k Before / After

### `MongoDBServer.Launcher` vs 수동 Container 생성

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

### `Flow.log("label")` vs 수동 Logging

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

### `CoroutineCrudRepository` Flow와 Cancellation 전파

`PersonCoroutineRepository.findAllByFirstname()`가 반환하는 `Flow<Person>`은
`asFlow()`로 변환한 Reactor `Flux`입니다. coroutine scope가 cancel되면
upstream `Flux` subscription이 즉시 cancel되고 MongoDB cursor가 해제됩니다.

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

### `@Tailable` Cursor와 Backpressure

`PersonCoroutineRepository.findWithTailableCursorBy()`는 MongoDB tailable cursor를
`Flux<Person>`으로 노출합니다. 이를 `.asFlow()`로 변환하면 `buffer`, `conflate` 같은
coroutine backpressure operator로 소비 속도를 제어할 수 있습니다.

```kotlin
repository.findWithTailableCursorBy()
    .asFlow()
    .buffer(capacity = 10)          // buffer up to 10 items
    .collect { person ->
        processSlowly(person)       // the tailable cursor waits even if consumption is slow
    }
```

## 빌드와 테스트

```bash
./gradlew :spring-data-mongodb-coroutines:test
```
