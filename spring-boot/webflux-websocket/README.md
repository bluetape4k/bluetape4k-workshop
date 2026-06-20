# Webflux & Websockets Demo

[한국어](README.ko.md) | English

## What this example shows

This module demonstrates raw WebFlux WebSocket streaming without STOMP. A browser connects to
`/event-emitter`, `ReactiveWebSocketHandler` turns generated quote JSON into WebSocket text messages, and
the same `QuoteGenerator` also backs the `/quotes` NDJSON HTTP routes used by tests.

## Architecture Diagram

![WebFlux WebSocket quote architecture](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-readme-architecture-01.png)

The architecture separates the static page, WebSocket handler mapping, quote generation, NDJSON routes, and
Netty runtime tuning.

## Quote Streaming Flow

![WebFlux WebSocket quote streaming flow](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-readme-sequence-01.png)

Original source: [sample-webflux-websockets](https://github.com/ketangit/sample-webflux-websockets)

## Introduction

This example demonstrates asynchronous quote delivery over WebSocket and coroutine-friendly HTTP streaming
over `application/x-ndjson`.

## Main Components

| Class | Role |
|---|---|
| `ReactiveWebSocketConfiguration` | Maps the handler to `/event-emitter` and registers the `WebSocketHandlerAdapter` bean |
| `ReactiveWebSocketHandler` | Receives a `WebSocketSession` and sends the Quote stream as text messages |
| `QuoteGenerator` | Service that periodically generates prices for seven stock tickers such as APPL, TSLA, and GOOG |
| `SampleWebfluxRouter` | Configures HTTP routes |
| `NettyConfig` | Configures the Netty server |
| `Quote` | Data model containing ticker, price, and timestamp |
| `Event` | Event wrapper that combines traceId (UUID v7) + a list of Quotes |

## Data Model

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

## WebSocket Handler Behavior

`ReactiveWebSocketHandler` subscribes to a Flux emitted every two seconds by `QuoteGenerator.fetchQuoteStringAsFlux(Duration.ofSeconds(2))`, sends it to the session, and also logs messages received from the client.

```kotlin
override fun handle(session: WebSocketSession): Mono<Void> {
    val flux = quoteGenerator
        .fetchQuoteStringAsFlux(Duration.ofSeconds(2))
        .map { quoteStr -> session.textMessage(quoteStr) }

    return session.send(flux)
        .and(session.receive().map { it.payloadAsText }.log())
}
```

`QuoteGenerator` provides both Reactor `Flux` (synchronous) and Kotlin `Flow` (coroutine) styles. It applies `conflate()` for backpressure handling, equivalent to Flow's `onBackpressureDrop()`.

## How to Run

```bash
./gradlew :webflux-websocket:bootRun
# WebSocket endpoint: ws://localhost:8080/event-emitter
```

## References

- [Official Spring WebFlux WebSocket documentation](https://docs.spring.io/spring-framework/reference/web/webflux-websocket.html)
- Original source: [sample-webflux-websockets](https://github.com/ketangit/sample-webflux-websockets)
