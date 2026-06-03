# Webflux & Websockets Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Webflux & Websockets Demo** as a runnable Spring Boot application feature workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Webflux & Websockets Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springboot` as the source of truth when comparing this README with the code.

## Sequence Diagram

![Webflux & Websockets Demo sequence diagram](../../docs/images/readme-diagrams/spring-boot-webflux-websocket-sequence-01.png)

Original source: [sample-webflux-websockets](https://github.com/ketangit/sample-webflux-websockets)

## Introduction

This example demonstrates asynchronous WebSocket communication with WebFlux.

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
