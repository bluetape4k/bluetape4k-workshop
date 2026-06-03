# Spring Webflux with Bucket4j and Redis

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Webflux with Bucket4j and Redis** as a runnable rate limiting workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Webflux with Bucket4j and Redis Graphviz architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-redis-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.ratelimit` as the source of truth when comparing this README with the code.

## Sequence Diagram

![Spring Webflux with Bucket4j and Redis sequence diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-redis-sequence-01.png)

## application.yml Configuration Example

```yaml
spring:
  data:
    redis:
      host: ${testcontainers.redis.host}
      port: ${testcontainers.redis.port}
      lettuce:
        pool:
          enabled: true

bucket4j:
  enabled: true
  cache-to-use: redis-lettuce          # Use the Lettuce-based Redis store
  filters:
    - cache-name: buckets
      filter-method: webflux           # WebFlux (asynchronous) filter mode
      url: .*
      rate-limits:
        - bandwidths:
            - capacity: 5              # Maximum number of bucket tokens
              time: 10
              unit: seconds            # Allow 5 requests per 10 seconds
```

## Key Components

| Class / File | Role |
|---------------|------|
| `Bucket4jRedisApplication.kt` | Spring Boot entry point |
| `LettuceConfiguration.kt` | Registers the `RedisClient` bean (injects the Testcontainers URL) |
| `CoroutineController.kt` | `suspend`-based `GET /coroutines/hello` and `GET /coroutines/world` endpoints |
| `ReactiveController.kt` | `Mono`-based `GET /reactive/hello` and `GET /reactive/world` endpoints |
| `DebugMetricHandler.kt` | Bucket4j metric debug handler |
| `application.yml` | Redis connection + Bucket4j WebFlux filter configuration |
| `CoroutineRateLimitTest.kt` | Rate Limit integration test for coroutine endpoints |
| `ReactiveRateLimitTest.kt` | Rate Limit integration test for reactive endpoints |

## Comparison with the Caffeine Approach

| Item | Caffeine (WebMVC) | Redis (WebFlux) |
|------|-------------------|-----------------|
| Store | In-memory (single instance) | Redis (distributable) |
| Sync/Async | Synchronous (blocking) | Asynchronous (non-blocking) |
| Scale-out | No | Yes (shared bucket state) |
| `cache-to-use` Setting | `jcache` | `redis-lettuce` |
| `filter-method` | `servlet` | `webflux` |

## Build and Test

```bash
./gradlew :bucket4j-redis:test
./gradlew :bucket4j-redis:test --tests "io.bluetape4k.workshop.bucket4j.controller.CoroutineRateLimitTest"
```
