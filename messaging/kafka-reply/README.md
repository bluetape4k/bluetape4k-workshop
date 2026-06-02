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

`ReplyingKafkaTemplate`을 이용한 Kafka 요청-응답(Request-Reply) 패턴 예제입니다.
bluetape4k의 `CompletableFuture.onSuccess/onFailure`, `uninitialized()`, `KLoggingChannel`을 활용합니다.
Spring Kafka 4 호환 Bluetape4k 모듈은 `bluetape4k-kafka4`를 사용합니다.

## 아키텍처

![kafka reply Sequence Flow diagram](../../docs/images/readme-diagrams/messaging-kafka-reply-sequence-01.png)

## 주요 구성

| 클래스 | 역할 |
|---|---|
| `PingController` | `ReplyingKafkaTemplate.sendAndReceive()` 로 ping 발송 후 응답 대기 |
| `PongHandler` | `@KafkaListener` + `@SendTo` 로 ping 수신 → pong 응답 |
| `PingApplication` | Ping 앱 설정 |
| `PongApplication` | Pong 앱 설정 |

## 사용된 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `CompletableFuture.onSuccess/onFailure` | `bluetape4k-coroutines` | `PingController.ping()` | Java Future에 Kotlin 스타일 콜백 확장 |
| `bluetape4k-kafka4` dependency | `bluetape4k-kafka4` | `build.gradle.kts` | Spring Kafka 4 예제에서 Bluetape4k Kafka 확장을 사용할 수 있는 호환 아티팩트 |
| `uninitialized()` | `bluetape4k-core` | `PingController` | 타입 안전 lateinit 초기화 대체 |
| `KLoggingChannel` | `bluetape4k-logging` | companion object | 코루틴 컨텍스트 포함 구조적 로깅 |

## Bluetape4k boundary

`messaging/kafka-reply`는 실제 요청-응답 프로토콜을 Spring Kafka의 `ReplyingKafkaTemplate.sendAndReceive()`로 유지합니다.
현재 검증된 `bluetape4k-kafka4` API에는 `ReplyingKafkaTemplate`을 대체하는 request/reply 전용 추상화가 없으므로, 이 예제는 Spring Kafka의 프로토콜 primitive와 Bluetape4k의 Future/coroutine ergonomics를 함께 사용합니다.

## bluetape4k Before / After

### `CompletableFuture.onSuccess/onFailure` vs 전통적 콜백

```kotlin
// Before — addCallback (deprecated) 또는 thenAccept/exceptionally
replyFuture.addCallback(
    { result -> logger.info("Sent ok: $result") },
    { e -> logger.error("Failed: ${e.message}") }
)

// After — bluetape4k onSuccess / onFailure 확장 (체이닝 가능)
replyFuture
    .onSuccess { result -> log.info { "callback result: $result" } }
    .onFailure { e -> log.error(e) { "callback exception." } }
```

### 요청-응답 처리

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

// Pong 쪽 — @SendTo로 자동 응답
@KafkaListener(groupId = "pong", topics = [TOPIC_PINGPONG])
@SendTo
fun handle(request: String): String {
    log.info { "Received: $request" }
    return "pong at ${LocalDateTime.now()}"
}
```

## 실행

```bash
./gradlew :messaging-kafka-reply:bootRun
# HTTP 파일로 테스트
# GET http://localhost:8080/ping
```

## 관련 모듈

- [`messaging/kafka`](../kafka) — 기본 Producer/Consumer 패턴

## 참고

- [Spring Kafka ReplyingKafkaTemplate](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html#replying-template)
- [bluetape4k-coroutines](https://github.com/bluetape4k/bluetape4k-projects)
