# Webflux & Websockets Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Webflux & Websockets Demo**를 실행 가능한 Spring Boot 애플리케이션 기능 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Webflux & Websockets Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼으세요.

## 흐름 다이어그램

1. `spring-boot-webflux-websocket`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업은 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 에셋이 있는 모듈은 아래 이미지가 상호작용 순서를 보여 주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![Webflux & Websockets Demo sequence diagram](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-sequence-01.png)

원본 소스: [sample-webflux-websockets](https://github.com/ketangit/sample-webflux-websockets)

## 소개

이 예제는 WebFlux를 사용한 비동기 WebSocket 통신을 보여 줍니다.

## WebSocket 연결과 메시지 흐름

![WebSocket diagram](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-sequence-01.png)

## 주요 구성 요소

| 클래스 | 역할 |
|---|---|
| `ReactiveWebSocketConfiguration` | handler를 `/event-emitter`에 매핑하고 `WebSocketHandlerAdapter` bean을 등록합니다 |
| `ReactiveWebSocketHandler` | `WebSocketSession`을 받아 Quote stream을 text message로 전송합니다 |
| `QuoteGenerator` | APPL, TSLA, GOOG 같은 7개 stock ticker의 가격을 주기적으로 생성하는 service입니다 |
| `SampleWebfluxRouter` | HTTP route를 설정합니다 |
| `NettyConfig` | Netty server를 설정합니다 |
| `Quote` | ticker, price, timestamp를 담는 data model입니다 |
| `Event` | traceId(UUID v7)와 Quote 목록을 결합하는 event wrapper입니다 |

## 데이터 모델

```kotlin
data class Quote(
    val ticker: String,
    val price: BigDecimal,
    val instant: Instant = Instant.now(),
)

data class Event(
    val id: String,       // UUID v7 based traceId
    val data: List<Quote>,
)
```

## WebSocket Handler 동작

`ReactiveWebSocketHandler`는 `QuoteGenerator.fetchQuoteStringAsFlux(Duration.ofSeconds(2))`가 2초마다 내보내는 Flux를 구독하고 session으로 전송하며, client에서 받은 메시지도 log로 남깁니다.

```kotlin
override fun handle(session: WebSocketSession): Mono<Void> {
    val flux = quoteGenerator
        .fetchQuoteStringAsFlux(Duration.ofSeconds(2))
        .map { quoteStr -> session.textMessage(quoteStr) }

    return session.send(flux)
        .and(session.receive().map { it.payloadAsText }.log())
}
```

`QuoteGenerator`는 Reactor `Flux`(동기)와 Kotlin `Flow`(coroutine) 스타일을 모두 제공합니다. backpressure 처리를 위해 `conflate()`를 적용하며, 이는 Flow의 `onBackpressureDrop()`과 같은 의미입니다.

## 실행 방법

```bash
./gradlew :webflux-websocket:bootRun
# WebSocket endpoint: ws://localhost:8080/event-emitter
```

## 참고

- [Official Spring WebFlux WebSocket documentation](https://docs.spring.io/spring-framework/reference/web/webflux-websocket.html)
- 원본 소스: [sample-webflux-websockets](https://github.com/ketangit/sample-webflux-websockets)
