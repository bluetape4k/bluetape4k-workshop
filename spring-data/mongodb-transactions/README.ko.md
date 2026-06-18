# Spring Data MongoDB Transactions

[English](README.md) | 한국어

이 모듈은 같은 `Process` state machine을 대상으로 imperative, reactive, coroutine MongoDB
transaction 스타일을 비교합니다. 각 service는 process를 `CREATED`로 만들고 `ACTIVE`를 거쳐,
검증이 성공하면 `DONE`을 commit하고 검증이 실패하면 `CREATED`로 rollback합니다.

## 이 예제가 보여 주는 것

![MongoDB transaction architecture](../../docs/images/readme-diagrams/spring-data-mongodb-transactions-readme-architecture-01.png)

테스트는 `MongoDBServer.Launcher.mongoDB`를 공유하고 blocking 또는 reactive Mongo client를
생성합니다. Imperative test는 `MongoTransactionManager`를 설치하고, reactive 및 coroutine
test는 `ReactiveMongoTransactionManager`를 설치합니다.

## Transaction state flow

![MongoDB transaction state flow](../../docs/images/readme-diagrams/spring-data-mongodb-transactions-readme-flow-01.png)

| Style | Service | Transaction boundary |
|---|---|---|
| Imperative | `TransitionService` | `MongoTransactionManager`를 사용하는 `@Transactional fun run(id)` |
| Reactive chain | `ReactiveTransitionService` | 선언적 transaction boundary가 없는 Reactor chain |
| Reactive managed | `ReactiveManagedTransitionService` | Reactor context session binding을 사용하는 `@Transactional fun run(id): Mono<Int>` |
| Coroutine managed | `CoroutineManagedTransitionService` | Reactor context가 coroutine 실행으로 bridge되는 `@Transactional suspend fun run(id)` |

`verify(process)`는 세 번째 process id마다 예외를 던집니다. 성공한 실행은 `DONE`을 저장하고,
실패한 실행은 저장된 document를 `CREATED`로 유지하므로 rollback을 검증할 수 있습니다.

## Process document

| Field | Role |
|---|---|
| `id` | atomic counter로 생성되는 integer process id |
| `state` | `UNKNOWN`, `CREATED`, `ACTIVE`, `DONE` |
| `transitionCount` | `start()`와 `finish()` update에서 증가 |

## Build and test

```bash
./gradlew :spring-data:mongodb-transactions:test
```

## 참고

- [Spring Data MongoDB transaction sample](https://github.com/spring-projects/spring-data-examples/tree/main/mongodb/transactions/README.md)
