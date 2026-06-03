# Kafka Reply Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Kafka Reply Demo** as a runnable message-driven workflow workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Kafka Reply Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/messaging-kafka-reply-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.messaging` as the source of truth when comparing this README with the code.

## Flow Diagram

1. Prepare the local runtime required by `messaging-kafka-reply`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![Kafka Reply Demo sequence diagram](../../docs/images/readme-diagrams/messaging-kafka-reply-sequence-01.png)

This example demonstrates the Kafka request-reply pattern with `ReplyingKafkaTemplate`.
It uses bluetape4k's `CompletableFuture.onSuccess/onFailure`, `uninitialized()`, and `KLoggingChannel`.
The Spring Kafka 4-compatible Bluetape4k module uses `bluetape4k-kafka4`.

## Key Components

| Class | Role |
|---|---|
| `PingController` | Sends a ping with `ReplyingKafkaTemplate.sendAndReceive()` and waits for the reply |
| `PongHandler` | Receives the ping with `@KafkaListener` + `@SendTo`, then returns a pong reply |
| `PingApplication` | Ping app configuration |
| `PongApplication` | Pong app configuration |

## Used bluetape4k Features

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `CompletableFuture.onSuccess/onFailure` | `bluetape4k-coroutines` | `PingController.ping()` | Kotlin-style callback extensions for Java Future |
| `bluetape4k-kafka4` dependency | `bluetape4k-kafka4` | `build.gradle.kts` | Compatible artifact that enables Bluetape4k Kafka extensions in Spring Kafka 4 examples |
| `uninitialized()` | `bluetape4k-core` | `PingController` | Type-safe alternative to `lateinit` initialization |
| `KLoggingChannel` | `bluetape4k-logging` | companion object | Structured logging with coroutine context |

## Bluetape4k boundary

`messaging/kafka-reply` keeps the actual request-reply protocol on Spring Kafka's `ReplyingKafkaTemplate.sendAndReceive()`.
The currently verified `bluetape4k-kafka4` API does not provide a dedicated request/reply abstraction that replaces `ReplyingKafkaTemplate`, so this example combines Spring Kafka's protocol primitive with Bluetape4k's Future/coroutine ergonomics.

## bluetape4k Before / After

### `CompletableFuture.onSuccess/onFailure` vs Traditional Callbacks

```kotlin
// Before — addCallback (deprecated) or thenAccept/exceptionally
replyFuture.addCallback(
    { result -> logger.info("Sent ok: $result") },
    { e -> logger.error("Failed: ${e.message}") }
)

// After — bluetape4k onSuccess / onFailure extensions (chainable)
replyFuture
    .onSuccess { result -> log.info { "callback result: $result" } }
    .onFailure { e -> log.error(e) { "callback exception." } }
```

### Request-Reply Handling

```kotlin
@GetMapping("/ping")
suspend fun ping(): String {
    val record = ProducerRecord<String, String>(TOPIC_PINGPONG, "ping")
    val replyFuture = template.sendAndReceive(record)

    replyFuture
        .onSuccess { log.info { "Sent ok: $it" } }
        .onFailure { log.error(it) { "Send failed" } }

    val sendResult = replyFuture.sendFuture?.await()
    val consumerRecord = replyFuture.await()         // coroutines.future.await()
    return consumerRecord.value()
}

// Pong side — automatically replies with @SendTo
@KafkaListener(groupId = "pong", topics = [TOPIC_PINGPONG])
@SendTo
fun handle(request: String): String {
    log.info { "Received: $request" }
    return "pong at ${LocalDateTime.now()}"
}
```

## Running

```bash
./gradlew :messaging-kafka-reply:bootRun
# Test with an HTTP file
# GET http://localhost:8080/ping
```

## Related Modules

- [`messaging/kafka`](../kafka) — Basic producer/consumer pattern

## References

- [Spring Kafka ReplyingKafkaTemplate](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html#replying-template)
- [bluetape4k-coroutines](https://github.com/bluetape4k/bluetape4k-projects)
