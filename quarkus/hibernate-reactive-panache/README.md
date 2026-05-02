# hibernate-reactive-panache

This project uses Quarkus, the Supersonic Subatomic Java Framework.

## 아키텍처 흐름

```mermaid
sequenceDiagram
    participant 클라이언트
    participant FruitResource
    participant FruitRepository
    participant Hibernate Reactive
    participant DB as PostgreSQL

    클라이언트->>FruitResource: GET /fruits
    FruitResource->>FruitRepository: listAll()
    FruitRepository->>Hibernate Reactive: find all
    Hibernate Reactive->>DB: SELECT * FROM Fruit
    DB-->>Hibernate Reactive: ResultSet
    Hibernate Reactive-->>FruitRepository: Uni<List<Fruit>>
    FruitRepository-->>FruitResource: Uni<List<Fruit>>
    FruitResource-->>클라이언트: JSON 응답

    클라이언트->>FruitResource: POST /fruits (Fruit JSON)
    FruitResource->>FruitRepository: persist(fruit)
    FruitRepository->>Hibernate Reactive: @WithTransaction insert
    Hibernate Reactive->>DB: INSERT INTO Fruit
    DB-->>Hibernate Reactive: 저장 완료
    Hibernate Reactive-->>FruitResource: Uni<Fruit>
    FruitResource-->>클라이언트: 생성된 Fruit JSON
```

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
gradle quarkusDev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
gradle build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

The application is now runnable using `java -jar build/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
gradle build -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar build/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
gradle build -Dquarkus.native.enabled=true
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
gradle build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./build/hibernate-reactive-panache-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/gradle-tooling>.

## Related Guides

- Kotlin ([guide](https://quarkus.io/guides/kotlin)): Write your services in Kotlin

## Provided Code

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)
