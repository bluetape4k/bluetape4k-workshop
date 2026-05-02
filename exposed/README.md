# Kotlinx Exposed Demo

Kotlinx [Exposed](https://github.com/JetBrains/Exposed) 를 이용한 Data Access 예제입니다.

## 서브모듈 구성

```mermaid
flowchart LR
    subgraph 공통["공통 모듈"]
        domain["domain\n매핑 패턴·SQL DSL 테스트"]
    end

    subgraph 웹서버["웹 서버 모듈"]
        webflux["sql-webflux-coroutines\nWebFlux + Coroutines"]
        vthread["sql-web-virtualthread\nWebMVC + Virtual Threads"]
        dao["dao-web-transaction\nDAO + @Transactional"]
        spring["spring-transaction\nSpring Transaction 통합"]
    end

    domain -->|테스트 인프라 제공| webflux
    domain -->|테스트 인프라 제공| vthread
    domain -->|테스트 인프라 제공| dao
    domain -->|테스트 인프라 제공| spring

    webflux -->|newSuspendedTransaction| DB[(데이터베이스\nH2 / MySQL)]
    vthread -->|VirtualThread + transaction| DB
    dao -->|@Transactional + DAO| DB
    spring -->|SpringTransactionManager| DB
```

## sql-webflux-coroutines

Spring Webflux + Kotlin Coroutines + Exposed 를 이용하여 H2, MySQL 데이터베이스 작업을 수행하는 예제입니다.

Exposed 의 `newSuspendTransaction` 을 활용하여, suspend 함수 내에서 트랜잭션을 수행할 수 있습니다.

## sql-web-virtualthread

Spring WebMVC + Virtual Threads + Exposed 를 이용하여 H2, MySQL 데이터베이스 작업을 수행하는 예제입니다.

`Bluetape4k`의 `virtualFuture` 와 Exposed 의 `transaction` 을 활용하여, Virtual Threads 내에서 트랜잭션을 수행할 수 있습니다.

## dao-web-transaction

Spring Boot + Exposed DAO 패턴을 사용한 CRUD 예제입니다. `@Transactional` 기반 트랜잭션 관리를 보여줍니다.

## spring-transaction

Spring Transaction Manager와 Exposed를 통합하는 예제입니다.
