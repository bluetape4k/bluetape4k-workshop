# STOMP WebSocket 예제

Spring Boot에서 STOMP 프로토콜을 사용하는 WebSocket 서버 예제입니다.
Virtual Thread를 적용한 Tomcat 위에서 동작합니다.

## 구성

| 클래스 | 역할 |
|---|---|
| `WebSocketConfig` | STOMP 엔드포인트 및 메시지 브로커 설정 |
| `TomcatConfig` | Tomcat에 Virtual Thread Executor 적용 |
| `GreetingController` | `@MessageMapping("/hello")` → `/topic/greetings` 발행 |
| `HelloMessage` | 클라이언트 → 서버 메시지 모델 |
| `Greeting` | 서버 → 클라이언트 응답 모델 |

## 핵심 설정

```kotlin
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/gs-guide-websocket")
    }
    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }
}
```

## 실행

```bash
./gradlew :stomp-websocket:bootRun
```

## 참고

- [Spring STOMP WebSocket Guide](https://spring.io/guides/gs/messaging-stomp-websocket)
- [Spring WebSocket 공식 문서](https://docs.spring.io/spring-framework/reference/web/websocket.html)
