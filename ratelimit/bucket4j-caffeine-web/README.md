# Spring Boot WebMVC with Bucket4j and Caffeine Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Boot WebMVC with Bucket4j and Caffeine Demo** as a runnable rate limiting workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Boot WebMVC with Bucket4j and Caffeine Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.ratelimit` as the source of truth when comparing this README with the code.

## Sequence Diagram

![Spring Boot WebMVC with Bucket4j and Caffeine Demo sequence diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-sequence-01.png)

## application.yml Configuration Example

```yaml
spring:
  cache:
    jcache:
      provider: com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider
    cache-names:
      - buckets
    caffeine:
      spec: maximumSize=1000000,expireAfterAccess=3600s

bucket4j:
  enabled: true
  filters:
    - cache-name: buckets
      url: .*                      # Apply to all URLs
      rate-limits:
        - bandwidths:
            - capacity: 10         # Maximum number of bucket tokens
              refill-capacity: 1   # Tokens refilled each interval
              time: 1
              unit: seconds
              initial-capacity: 20 # Initial token count (allows burst)
              refill-speed: interval
```

## Key Components

| Class / File | Role |
|---------------|------|
| `CaffeineApplication.kt` | Spring Boot entry point, `@SpringBootApplication` |
| `IndexController.kt` | Provides the `GET /hello` and `GET /world` endpoints |
| `application.yml` | Caffeine JCache + Bucket4j filter configuration |
| `ServletRateLimitTest.kt` | Rate Limit integration test based on `@SpringBootTest` |

## Constraints and Alternatives

| Item | Details |
|------|------|
| Store | Caffeine JCache — **synchronous (blocking)** only |
| Applicable Server | Spring Boot WebMVC (Servlet-based) |
| Asynchronous Alternatives | Redis (`LettuceBasedProxyManager`), Hazelcast |
| Virtual Threads Alternative | `spring.threads.virtual.enabled=true` + WebMVC |

## Build and Test

```bash
./gradlew :bucket4j-caffeine-web:test
```
