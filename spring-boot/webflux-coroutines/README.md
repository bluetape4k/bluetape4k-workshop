# Spring WebFlux + Coroutines

Spring WebFlux 환경에서 Kotlin Coroutines를 사용하는 예제입니다.
Reactor를 직접 다루면 잘못된 스레드 전환으로 Meltdown이 발생할 수 있는데, Coroutines를 활용하면 이를 안전하게 해결할 수 있습니다.

## 구현 방식 비교

세 가지 Controller로 Dispatcher 전략에 따른 차이를 보여줍니다:

| Controller | Dispatcher 전략 | 설명 |
|---|---|---|
| `DefaultCoroutineController` | `Dispatchers.IO` | 기본 I/O 디스패처로 블로킹 작업 처리 |
| `IOCoroutineController` | `Dispatchers.IO` (명시적) | I/O 집약적 작업에 최적화 |
| `VTCoroutineController` | Virtual Thread 기반 Dispatcher | Java Virtual Thread를 CoroutineDispatcher로 활용 |

또한 어노테이션 방식 외에 함수형 라우터(WebFlux Handler) 방식도 포함합니다:
- `CoroutineHandler` — `coRouter` DSL 기반 함수형 엔드포인트

## Dispatcher 전략 흐름

```mermaid
flowchart LR
    클라이언트 -->|HTTP 요청| 라우터

    subgraph 컨트롤러 레이어
        라우터 --> DC[DefaultCoroutineController\nDispatchers.IO]
        라우터 --> IC[IOCoroutineController\nDispatchers.IO 명시]
        라우터 --> VT[VTCoroutineController\nVirtualThread Dispatcher]
        라우터 --> CH[CoroutineHandler\ncoRouter DSL]
    end

    subgraph Dispatcher 레이어
        DC -->|withContext| IO스레드풀[IO 스레드 풀]
        IC -->|withContext| IO스레드풀
        VT -->|asCoroutineDispatcher| 가상스레드[Virtual Thread Executor]
        CH -->|coRouter| IO스레드풀
    end

    IO스레드풀 -->|suspend 응답| 클라이언트
    가상스레드 -->|suspend 응답| 클라이언트
```

## 요청 처리 흐름

```mermaid
sequenceDiagram
    participant 클라이언트
    participant NettyServer
    participant CoroutineController
    participant Dispatcher
    participant 외부서비스

    클라이언트->>NettyServer: HTTP GET /api/delay
    NettyServer->>CoroutineController: suspend fun handle()
    CoroutineController->>Dispatcher: withContext(Dispatchers.IO)
    Dispatcher->>외부서비스: 비동기 I/O 호출
    외부서비스-->>Dispatcher: 결과 반환
    Dispatcher-->>CoroutineController: resume coroutine
    CoroutineController-->>NettyServer: ResponseEntity
    NettyServer-->>클라이언트: HTTP 200 OK
```

## Virtual Thread Dispatcher 설정

```kotlin
val Dispatchers.VirtualThread: CoroutineDispatcher
    get() = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
```

## 실행

```bash
./gradlew :webflux-coroutines:bootRun
```

## 참고

- [Spring WebFlux + Coroutines 공식 문서](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)
- [Reactor Meltdown 설명](https://blog.frankel.ch/project-reactor-meltdown/)
