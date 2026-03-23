# Exposed SQL + Spring WebMVC + Virtual Threads

Spring WebMVC(Tomcat)와 Java Virtual Threads, JetBrains Exposed SQL DSL을 조합하여 Actor·Movie CRUD REST API를 구현하는 예제입니다.

## Virtual Thread 기반 요청 처리 흐름

```mermaid
flowchart LR
    클라이언트([HTTP 클라이언트]) --> Tomcat

    subgraph 서버["Spring WebMVC + Tomcat"]
        Tomcat["Tomcat\nVirtualThreadExecutor"]
        VT["Virtual Thread\n(요청별 1개)"]
        Controller["ActorController\nMovieController\nMovieActorsController"]
        Tomcat -->|요청 할당| VT
        VT --> Controller
    end

    subgraph 비동기설정["Virtual Thread 설정"]
        TomcatConfig["TomcatConfig\nnewVirtualThreadPerTaskExecutor()"]
        AsyncConfig["AsyncConfig\nAsyncTaskExecutor"]
        SchedulingConfig["SchedulingConfig\nScheduler"]
    end

    subgraph 리포지토리["데이터 레이어 (Exposed SQL DSL)"]
        ActorRepo["ActorRepository\ntransaction 블록"]
        MovieRepo["MovieRepository\ntransaction 블록"]
        Actors["Actors 테이블"]
        Movies["Movies 테이블"]
        ActorsInMovies["ActorsInMovies 테이블\n(다대다)"]
    end

    TomcatConfig -->|설정| Tomcat
    Controller --> ActorRepo
    Controller --> MovieRepo
    ActorRepo -->|transaction| Actors
    MovieRepo -->|transaction| Movies
    ActorsInMovies --> Actors
    ActorsInMovies --> Movies
    Actors --> DB[(H2 / MySQL)]
    Movies --> DB
```

## 기술 스택

| 기술 | 역할 |
|---|---|
| Spring WebMVC + Tomcat | 동기 HTTP 서버 (Virtual Thread 적용) |
| Java Virtual Threads | 경량 스레드 기반 동시성 |
| Exposed SQL DSL | 타입 안전 SQL 쿼리 |
| H2 (In-Memory) | 기본 데이터베이스 |
| SpringDoc OpenAPI | Swagger UI (`/swagger-ui.html`) |

## Virtual Thread 설정

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

```kotlin
// Tomcat 요청 처리에 Virtual Thread 적용
@Bean
fun protocolHandlerVirtualThreadExecutorCustomizer(): TomcatProtocolHandlerCustomizer<*> =
    TomcatProtocolHandlerCustomizer { it.executor = Executors.newVirtualThreadPerTaskExecutor() }

// @Async 작업에도 Virtual Thread 적용
@Bean
fun asyncTaskExecutor(): AsyncTaskExecutor =
    TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor())
```

## REST API 엔드포인트

| Method | 경로 | 설명 |
|---|---|---|
| GET | `/actors` | 전체 배우 목록 |
| GET | `/actors/{id}` | 배우 조회 |
| POST | `/actors` | 배우 생성 |
| PUT | `/actors/{id}` | 배우 수정 |
| DELETE | `/actors/{id}` | 배우 삭제 |
| GET | `/movies` | 전체 영화 목록 |
| GET | `/movie-actors` | 영화-배우 연관 목록 |

## 실행

```bash
./gradlew :exposed-sql-web-virtualthread:bootRun
```

Swagger UI: http://localhost:8080/swagger-ui.html

## 참고

- [Virtual Threads in Spring Boot](https://spring.io/blog/2022/10/11/embracing-virtual-threads)
- [Exposed SQL DSL](https://github.com/JetBrains/Exposed/wiki/DSL)

There are two variants, one with H2 and one with Postgres. H2 is the easiest starting point because it is an
in-memory database.

### Running with H2

Run [MainWithH2.kt](src/main/kotlin/nl/toefel/blog/exposed/MainWithH2.kt). It will automatically:

1. create an in-memory H2 database
2. create the schema
3. load test data
4. start a API server at localhost:8080

### Running with Postgres

First start a Postgres database. If you have docker available, you can use:

    docker run --name exposed-db -p 5432:5432 -e POSTGRES_USER=exposed -e POSTGRES_PASSWORD=exposed -d postgres

Then run [MainWithPostgresAndHikari](src/main/kotlin/nl/toefel/blog/exposed/MainWithPostgresAndHikari.kt). It will:

1. create a HikariCP datasource connecting to the postgres database
2. create or update the schema
3. load test data if not already present
4. start a API server at localhost:8080

### Database diagram

![database-diagram](erd.png)

# Overview of code

Start by looking at how the database tables are described [in code](src/main/kotlin/nl/toefel/blog/exposed/db/).
The database structure is specified in code very similar to SQL DDL statements. No annotations or reflection required.

* [Actors.kt](src/main/kotlin/nl/toefel/blog/exposed/db/Actors.kt)
* [Movies.kt](src/main/kotlin/nl/toefel/blog/exposed/db/Movies.kt)
* [ActorsInMovies.kt](src/main/kotlin/nl/toefel/blog/exposed/db/ActorsInMovies.kt)

Notice that the database types `varchar`, `integer` and `date/datetime/timestamp` map to Kotlin types
`String` and `Int` and `joda.DateTime`. Using these definitions, we can write queries in a type-safe manner using a DSL.

[Router.kt](src/main/kotlin/nl/toefel/blog/exposed/rest/Router.kt) contains the REST api which uses the DSL
to serve requests. The executed SQL queries are logging at runtime for
debugging [logback.xml](src/main/resources/logback.xml).

To interact with the database, simply start a transaction block:

```kotlin
val actorCount = transaction {
    Actors.selectAll().count()
}
```

`transaction` uses the datasource that was configured
in [MainWithH2.kt](src/main/kotlin/nl/toefel/blog/exposed/MainWithH2.kt)
or [MainWithPostgresAndHikari](src/main/kotlin/nl/toefel/blog/exposed/MainWithPostgresAndHikari.kt).

You can query as much as you want within a transaction block, when it goes out of scope without
an error, it will automatically `commit()`. Leaving the scope with an exception automatically
triggers a `rollback()`.

The last statement of the `transaction` is returned, as in the example. Transaction blocks can be nested,
see [NestedTransactions](NestedTransactions.md) for details.

### Spring transaction management

Kotlin Exposed can use the spring transaction management. Import
the [spring-transaction](https://mvnrepository.com/artifact/org.jetbrains.exposed/spring-transaction?repo=kotlin-exposed).
dependency.

```groovy
    implementation "org.jetbrains.exposed:spring-transaction:0.16.1"
```

Then create the `org.jetbrains.exposed.spring.SpringTransactionManager` bean in your application config:

```kotlin
    @Bean
fun transactionManager(dataSource: HikariDataSource): SpringTransactionManager {
    val transactionManager = SpringTransactionManager(
        dataSource, DEFAULT_ISOLATION_LEVEL, DEFAULT_REPETITION_ATTEMPTS
    )
    return transactionManager
}
```

There is also
an [exposed-spring-boot-starter](https://github.com/JetBrains/Exposed/tree/master/exposed-spring-boot-starter).

# Using the REST apis

When started, you can use these URL's to interact with it:

    # fetch all actors
    curl http://localhost:8080/actors | python -m json.tool
    
    # fetch all actors with first name Angelina
    curl http://localhost:8080/actors?firstName=Angelina
    
    # add an actor
    curl -X POST http://localhost:8080/actors -H 'application/json' \
       -d '{"firstName":"Ousmane","lastName":"Dembele","dateOfBirth":"1975-05-10"}' 
    
    # delete an actor
    curl -X DELETE http://localhost:8080/actors/2
    
    
    # fetch all movies
    curl http://localhost:8080/movies
    
    # fetch a specific movie to see which actors are in it
    curl http://localhost:8080/movies/2

## More info

* [Exposed README](https://github.com/JetBrains/Exposed)
* [Exposed wiki](https://github.com/JetBrains/Exposed/wiki) with the docs.
* [Baeldung Guide to the Kotlin Exposed framework](https://www.baeldung.com/kotlin-exposed-persistence) older resource
* [How we use Kotlin with Exposed for SQL access at TouK](https://medium.com/@pjagielski/how-we-use-kotlin-with-exposed-at-touk-eacaae4565b5)
* [Bits and blobs of Kotlin/Exposed JDBC framework](https://medium.com/@OhadShai/bits-and-blobs-of-kotlin-exposed-jdbc-framework-f1ee56dc8840)
