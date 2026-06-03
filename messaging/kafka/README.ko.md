# Kafka Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Kafka Demo**를 실행 가능한 메시지 기반 워크플로우 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리나 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Kafka Demo Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/messaging-kafka-readme-architecture-01.png)

모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.messaging` 패키지 아래의 구현을 기준으로 삼습니다.

## 시퀀스 다이어그램

![Kafka Demo 시퀀스 다이어그램](../../docs/images/readme-diagrams/messaging-kafka-sequence-01.png)

Spring Kafka를 사용하는 기본 메시지 발행 및 소비 예제입니다.
bluetape4k의 `KafkaServer.Launcher`로 Testcontainers Kafka를 자동 시작하고, `bluetape4k-kafka4`의 `suspendSend()`로 Spring Kafka 4 발행 경로를 코루틴 친화적으로 처리합니다.

## 주요 구성 요소

| Class | Role |
|---|---|
| `KafkaApplication` | `KafkaServer.Launcher.kafka`로 Testcontainers Kafka를 자동 시작합니다. |
| `KafkaConfig` | `KafkaTemplate` bean을 구성합니다. |
| `GreetingController` | `KafkaTemplate.suspendSend()`로 메시지를 발행하는 `suspend` endpoint |
| `SimpleMessageHandler` | `TOPIC_SIMPLE`에서 string 메시지를 소비합니다. |
| `GreetingMessageHandler` | `TOPIC_GREETING`에서 JSON object 메시지를 소비합니다. |
| `LoggerMessageHandler` | 모든 topic을 로깅합니다. |

## 사용된 bluetape4k 기능

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `KafkaServer.Launcher.kafka` | `bluetape4k-testcontainers` | `KafkaApplication` companion | `application.yml` 없이 자동 시작되는 Testcontainers Kafka singleton |
| `KafkaOperations.suspendSend()` | `bluetape4k-kafka4` | `GreetingController` | suspend 함수 안에서 Spring Kafka 4 `KafkaTemplate` publish 결과를 코루틴 스타일로 기다립니다. |
| `KLoggingChannel` | `bluetape4k-logging` | All companion objects | 코루틴 컨텍스트를 포함한 구조적 로깅 |
| `uninitialized()` | `bluetape4k-core` | `GreetingController` | `lateinit` 초기화를 대체하는 타입 안전 대안 |

## bluetape4k Before / After

### 수동 Testcontainers 설정 대비 `KafkaServer.Launcher`

```kotlin
// Before — manual @DynamicPropertySource + KafkaContainer management
@SpringBootTest
class KafkaTest {
    companion object {
        @Container
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))

        @JvmStatic
        @DynamicPropertySource
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }
}

// After — KafkaServer.Launcher.kafka singleton (starts automatically on app startup)
@SpringBootApplication
class KafkaApplication {
    companion object: KLoggingChannel() {
        val kafka = KafkaServer.Launcher.kafka  // done in one line
    }
}
```

### `KLoggingChannel` — Coroutine Logging

```kotlin
// Before
private val logger = LoggerFactory.getLogger(SimpleMessageHandler::class.java)
logger.debug("Received message: {}", message)

// After — KLoggingChannel (lazy lambda + coroutine MDC context)
companion object: KLoggingChannel()
log.debug { "Received message: $message" }
```

## 메시지 발행 예시

```kotlin
// Publish a string message
kafkaTemplate.suspendSend(KafkaTopics.TOPIC_SIMPLE, "Hello Kafka")

// Publish a JSON object message
kafkaTemplate.suspendSend(KafkaTopics.TOPIC_GREETING, GreetingRequest("Alice", "Hello"))
```

## @KafkaListener — 코루틴 제약

> **Note**: `@KafkaListener` does not officially support `suspend` functions or Reactor.
> `CoroutineSimpleMessageHandler` is disabled (`// @Component`) and kept for reference.

```kotlin
// @Component disabled — KafkaListener does not support suspend
class CoroutineSimpleMessageHandler {
    @KafkaListener(topics = [TOPIC_SIMPLE])
    suspend fun handleWithCoroutines(message: String) { ... }  // does not work
}

// Alternative: wrap with mono { }
@KafkaListener(topics = [TOPIC_SIMPLE])
fun handleWithMono(message: String): Mono<Void> = mono {
    delay(100)
    null
}
```

## 실행

```bash
./gradlew :messaging-kafka:bootRun
# Or run tests
./gradlew :messaging-kafka:test
```

## 관련 모듈

- [`messaging/kafka-reply`](../kafka-reply) — `ReplyingKafkaTemplate`을 사용하는 request-reply 패턴

## 참고

- [Spring Kafka official docs](https://docs.spring.io/spring-kafka/reference/)
- [Apache Kafka](https://kafka.apache.org/documentation/)
- [bluetape4k-testcontainers](https://github.com/bluetape4k/bluetape4k-projects)
- [bluetape4k-kafka4](https://github.com/bluetape4k/bluetape4k-projects)
