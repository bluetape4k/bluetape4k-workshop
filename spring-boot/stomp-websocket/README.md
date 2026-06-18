# STOMP WebSocket Example

[한국어](README.ko.md) | English

## What this example shows

This module shows the smallest Spring Boot STOMP WebSocket path: a SockJS/STOMP client connects to
`/gs-guide-websocket`, sends `HelloMessage` to `/app/hello`, and receives a broadcast `Greeting` from
`/topic/greetings`. The example also keeps Tomcat on a virtual-thread executor so the blocking controller
delay is easy to see without turning the sample into a custom broker implementation.

## Architecture Diagram

![STOMP WebSocket architecture](../../docs/images/readme-diagrams/spring-boot-stomp-websocket-readme-architecture-01.png)

The architecture separates transport setup, application routing, the simple broker topic, and the test/static
clients that prove the same message contract.

## Greeting Flow

![STOMP WebSocket greeting flow](../../docs/images/readme-diagrams/spring-boot-stomp-websocket-readme-sequence-01.png)

This is a WebSocket server example that uses the STOMP protocol in Spring Boot.
It registers a SockJS endpoint, routes `/app` destinations to controller methods, and broadcasts `/topic`
messages through Spring's in-memory simple broker.

## Components

| Class | Role |
|---|---|
| `WebSocketConfig` | Configures STOMP endpoints and the message broker |
| `TomcatConfig` | Applies the Virtual Thread Executor to Tomcat |
| `GreetingController` | Publishes `@MessageMapping("/hello")` to `/topic/greetings` |
| `HelloMessage` | Client-to-server message model |
| `Greeting` | Server-to-client response model |

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

`TomcatConfig` applies a virtual-thread executor to Tomcat's protocol handler. In this sample the controller
contains a short blocking delay, so the configuration makes the runtime boundary explicit while the message
contract remains the usual Spring STOMP contract.

## Test

`GreetingIntegrationTest` creates a `StompSession` directly, sends a message to `/app/hello`, and verifies the response through a `/topic/greetings` subscription.

## References

- [Spring STOMP WebSocket Guide](https://spring.io/guides/gs/messaging-stomp-websocket)
- [Official Spring WebSocket documentation](https://docs.spring.io/spring-framework/reference/web/websocket.html)
