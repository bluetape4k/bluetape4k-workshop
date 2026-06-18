# Kafka Reply Demo

[한국어](README.ko.md) | English

`messaging/kafka-reply` demonstrates a Spring Kafka request-reply flow. A WebFlux endpoint sends `ping` through
`ReplyingKafkaTemplate`, `PongHandler` consumes the request from Kafka, and the reply is delivered back through the
template's reply listener container.

## Architecture

![Kafka reply architecture](../../docs/images/readme-diagrams/messaging-kafka-reply-readme-architecture-01.png)

The example intentionally keeps the protocol primitive in Spring Kafka. bluetape4k is used around that primitive for
coroutine/Future ergonomics, logging, `uninitialized()` injection fields, and the Testcontainers Kafka server.

## Request-Reply Flow

![Kafka reply request-reply flow](../../docs/images/readme-diagrams/messaging-kafka-reply-readme-request-reply-flow-01.png)

`PingController` waits for two results:

1. `replyFuture.sendFuture?.await()` confirms the request record was produced to `pingpong`.
2. `replyFuture.await()` waits until the `replies` listener container receives the `@SendTo` response.

## Key Components

| Component | Role |
|---|---|
| `KafkaApplicationKt` | Starts `PingApplication` and `PongApplication` in one process for the workshop demo. |
| `PingController` | Exposes `GET /ping`, sends `ping`, logs success/failure callbacks, and returns the reply body. |
| `ReplyingKafkaTemplate<String, String, String>` | Sends the request record and correlates the reply consumed by the reply listener container. |
| `listenerContainer("replies")` | Dedicated reply consumer container with group id `repliesGroup`; it is wired into `ReplyingKafkaTemplate`. |
| `PongHandler` | Listens on `pingpong` and returns `pong at <time>` with `@SendTo`. |
| `KafkaServer.Launcher.kafka` | Provides the Testcontainers Kafka bootstrap server used by both applications. |

## bluetape4k Usage

| Feature | Where | Why it matters |
|---|---|---|
| `bluetape4k-kafka4` | `build.gradle.kts` | Keeps the example on the Spring Kafka 4-compatible bluetape4k artifact line. |
| `CompletableFuture.onSuccess/onFailure` | `PingController.ping()` | Adds readable success/failure callbacks before awaiting the reply. |
| `kotlinx.coroutines.future.await()` | `PingController.ping()` | Lets the suspend endpoint await both send and reply futures without blocking the event loop. |
| `uninitialized()` | `PingController.template` | Avoids a nullable or `lateinit` property for Spring injection. |
| `KLoggingChannel` | Application and handler companions | Keeps structured logs consistent with other bluetape4k examples. |

## Request Handler

```kotlin
@GetMapping("/ping")
suspend fun ping(): String {
    val record = ProducerRecord<String, String>(TOPIC_PINGPONG, "ping")
    val replyFuture = template.sendAndReceive(record)

    replyFuture
        .onSuccess { result -> log.info { "callback result: $result" } }
        .onFailure { e -> log.error(e) { "callback exception." } }

    replyFuture.sendFuture?.await()
    return replyFuture.await().value()
}
```

## Reply Handler

```kotlin
@KafkaListener(groupId = "pong", topics = [PongApplication.TOPIC_PINGPONG])
@SendTo
fun handle(request: String): String {
    return "pong at " + LocalDateTime.now()
}
```

## Running

```bash
./gradlew :messaging-kafka-reply:bootRun
curl http://localhost:8080/ping
```

## Related Modules

- [`messaging/kafka`](../kafka) - basic publish/consume paths with `KafkaTemplate.suspendSend`.

## References

- [Spring Kafka ReplyingKafkaTemplate](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html#replying-template)
- [bluetape4k Projects](https://github.com/bluetape4k/bluetape4k-projects)
