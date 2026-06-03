# STOMP WebSocket Example

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **STOMP WebSocket Example** as a runnable Spring Boot application feature workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![STOMP WebSocket Example Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-stomp-websocket-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springboot` as the source of truth when comparing this README with the code.

## Flow Diagram

1. Prepare the local runtime required by `spring-boot-stomp-websocket`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![STOMP WebSocket Example sequence diagram](../../docs/images/readme-diagrams/spring-boot-stomp-websocket-sequence-01.png)

This is a WebSocket server example that uses the STOMP protocol in Spring Boot.
It runs on Tomcat with Virtual Threads enabled.

## Components

| Class | Role |
|---|---|
| `WebSocketConfig` | Configures STOMP endpoints and the message broker |
| `TomcatConfig` | Applies the Virtual Thread Executor to Tomcat |
| `GreetingController` | Publishes `@MessageMapping("/hello")` to `/topic/greetings` |
| `HelloMessage` | Client-to-server message model |
| `Greeting` | Server-to-client response model |

## STOMP Message Flow

![STOMP diagram](../../docs/images/readme-diagrams/spring-boot-stomp-websocket-sequence-01.png)

## Key Configuration

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

## Run

```bash
./gradlew :stomp-websocket:bootRun
```

## STOMP Protocol Concepts

STOMP (Simple Text Oriented Messaging Protocol) is a messaging protocol that runs on top of WebSocket. Unlike plain WebSocket, it provides a destination-based publish/subscribe model that simplifies integration with message brokers.

| Concept | Description |
|---|---|
| **CONNECT** | Client connects a STOMP session to the server |
| **SEND** | Client sends a message to the server (destination: `/app/...`) |
| **SUBSCRIBE** | Client subscribes to a specific topic (destination: `/topic/...`) |
| **MESSAGE** | Broker delivers a message to subscribers |
| **DISCONNECT** | Session closes |

### Destination Prefixes

| Prefix | Role |
|---|---|
| `/app` | Routes to `@MessageMapping` controller methods |
| `/topic` | Broadcasts to subscription topics managed by SimpleBroker |

## Message Data Model

```kotlin
// Client -> server
data class HelloMessage(val name: String)

// Server -> client
data class Greeting(val content: String)
```

## GreetingController Behavior

```kotlin
@Controller
class GreetingController {
    @MessageMapping("/hello")           // Receives SEND /app/hello
    @SendTo("/topic/greetings")         // Broadcasts to /topic/greetings subscribers
    fun greeting(message: HelloMessage): Greeting {
        return Greeting("Hello, ${message.name}!")
    }
}
```

## Virtual Thread Integration

`TomcatConfig` applies a Virtual Thread Executor to Tomcat so WebSocket handlers run on Virtual Threads. This enables efficient handling of large numbers of concurrent connections without exhausting the thread pool.

## Test

`GreetingIntegrationTest` creates a `StompSession` directly, sends a message to `/app/hello`, and verifies the response through a `/topic/greetings` subscription.

## References

- [Spring STOMP WebSocket Guide](https://spring.io/guides/gs/messaging-stomp-websocket)
- [Official Spring WebSocket documentation](https://docs.spring.io/spring-framework/reference/web/websocket.html)
