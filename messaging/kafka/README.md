# Kafka Demo

Spring Kafka를 사용하는 기본 메시지 발행·소비 예제입니다.
bluetape4k의 `KafkaServer.Launcher`로 Testcontainers Kafka를 자동 구동하고, `KLoggingChannel`과 `uninitialized()`를 활용합니다.

## 아키텍처

```mermaid
graph LR
    Client -->|HTTP POST /greeting| Controller
    Controller -->|KafkaTemplate.send| Kafka[(Kafka Broker)]
    Kafka -->|@KafkaListener| SimpleHandler[SimpleMessageHandler]
    Kafka -->|@KafkaListener| GreetingHandler[GreetingMessageHandler]
    Kafka -->|@KafkaListener| LoggerHandler[LoggerMessageHandler]
    KafkaServerLauncher[KafkaServer.Launcher] -->|autostart| Kafka
```

## 주요 구성

| 클래스 | 역할 |
|---|---|
| `KafkaApplication` | `KafkaServer.Launcher.kafka`로 Testcontainers Kafka 자동 구동 |
| `KafkaConfig` | `KafkaTemplate` 빈 구성 |
| `GreetingController` | `suspend` 엔드포인트 — `KafkaTemplate`으로 메시지 발행 |
| `SimpleMessageHandler` | `TOPIC_SIMPLE` 문자열 메시지 소비 |
| `GreetingMessageHandler` | `TOPIC_GREETING` JSON 객체 메시지 소비 |
| `LoggerMessageHandler` | 모든 토픽 로깅 |

## 사용된 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `KafkaServer.Launcher.kafka` | `bluetape4k-testcontainers` | `KafkaApplication` companion | Testcontainers Kafka 싱글톤 — `application.yml` 없이 자동 구동 |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | 코루틴 컨텍스트 포함 구조적 로깅 |
| `uninitialized()` | `bluetape4k-core` | `GreetingController` | 타입 안전 lateinit 초기화 대체 |

## bluetape4k Before / After

### `KafkaServer.Launcher` vs 수동 Testcontainers 설정

```kotlin
// Before — @DynamicPropertySource + KafkaContainer 수동 관리
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

// After — KafkaServer.Launcher.kafka 싱글톤 (앱 시작 시 자동 구동)
@SpringBootApplication
class KafkaApplication {
    companion object: KLoggingChannel() {
        val kafka = KafkaServer.Launcher.kafka  // 한 줄로 끝
    }
}
```

### `KLoggingChannel` — 코루틴 로깅

```kotlin
// Before
private val logger = LoggerFactory.getLogger(SimpleMessageHandler::class.java)
logger.debug("Received message: {}", message)

// After — KLoggingChannel (lazy lambda + 코루틴 MDC 컨텍스트)
companion object: KLoggingChannel()
log.debug { "Received message: $message" }
```

## 메시지 발행 예시

```kotlin
// 문자열 메시지 발행
kafkaTemplate.send(KafkaTopics.TOPIC_SIMPLE, "Hello Kafka")

// JSON 객체 메시지 발행
kafkaTemplate.send(KafkaTopics.TOPIC_GREETING, GreetingRequest("Alice", "안녕하세요"))
```

## @KafkaListener — 코루틴 제약

> **참고**: `@KafkaListener`는 `suspend` 함수와 Reactor를 공식 지원하지 않습니다.
> `CoroutineSimpleMessageHandler`는 비활성화 상태 (`// @Component`)로 참고용입니다.

```kotlin
// @Component 비활성화 — KafkaListener는 suspend 미지원
class CoroutineSimpleMessageHandler {
    @KafkaListener(topics = [TOPIC_SIMPLE])
    suspend fun handleWithCoroutines(message: String) { ... }  // 비동작
}

// 대안: mono { } 래핑
@KafkaListener(topics = [TOPIC_SIMPLE])
fun handleWithMono(message: String): Mono<Void> = mono {
    delay(100)
    null
}
```

## 실행

```bash
./gradlew :messaging-kafka:bootRun
# 또는 테스트
./gradlew :messaging-kafka:test
```

## 관련 모듈

- [`messaging/kafka-reply`](../kafka-reply) — `ReplyingKafkaTemplate`을 이용한 요청-응답 패턴

## 참고

- [Spring Kafka 공식 문서](https://docs.spring.io/spring-kafka/reference/)
- [Apache Kafka](https://kafka.apache.org/documentation/)
- [bluetape4k-testcontainers](https://github.com/bluetape4k/bluetape4k-projects)
