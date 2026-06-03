# Webflux & Websockets Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Webflux & Websockets Demo**를 실행 가능한 Spring Boot 애플리케이션 기능 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Webflux & Websockets Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼으세요.

## 시퀀스 다이어그램

![Webflux & Websockets Demo sequence diagram](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-sequence-01.png)

원본 소스: [sample-webflux-websockets](https://github.com/ketangit/sample-webflux-websockets)

## 소개

이 예제는 WebFlux를 사용한 비동기 WebSocket 통신을 보여 줍니다.

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
