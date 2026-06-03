# Rate Limiter Examples

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Rate Limiter Examples** as a runnable rate limiting workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Rate Limiter Examples Graphviz architecture diagram](../docs/images/readme-diagrams/ratelimit-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.ratelimit` as the source of truth when comparing this README with the code.

![Rate Limiter Examples architecture diagram](../docs/images/readme-diagrams/ratelimit-bucket4j-advanced-architecture-01.png)

## Flow Diagram

1. Prepare the local runtime required by `Rate Limiter Examples`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

![Rate Limiter Examples flow diagram](../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-diagram-01.png)

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![Rate Limiter Examples sequence diagram](../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-sequence-01.png)

## Submodule Structure

![ratelimit Architecture diagram](../docs/images/readme-diagrams/ratelimit-diagram-01.png)

## bucket4j-bluetape4k-webflux (Recommended)

This example uses the user-token-based RateLimiter provided by `bluetape4k-bucket4j`.

It supports both IP-based and user-token-based rate limiting, and uses Redis as the bucket store.

## bucket4j-caffeine-web

This example uses `bucket4j-spring-boot-starter`. It provides an IP-based Rate Limiter and is intended for local use.

## bucket4j-redis

This example uses `bucket4j-spring-boot-starter`. It provides an IP-based Rate Limiter.

## Module Comparison

| Item | `bucker4j-bluetape4k-webflux` | `bucket4j-redis` | `bucket4j-caffeine-web` |
|---|---|---|---|
| **Recommended?** | Recommended | Standard | Local/development use |
| **Identity Basis** | User token / IP | IP | IP |
| **Store** | Redis (Lettuce) | Redis (Lettuce) | Caffeine (in-memory) |
| **Stack** | WebFlux + coroutines | WebFlux + coroutines | WebMVC (Servlet) |
| **Distributed Support** | Yes | Yes | No (single node) |
| **Library** | `bluetape4k-bucket4j` | `bucket4j-spring-boot-starter` | `bucket4j-spring-boot-starter` |

## Rate Limit Strategy

### Token Bucket Algorithm

Bucket4j uses the Token Bucket algorithm. If tokens remain in the bucket, the request is allowed; if they are exhausted, it returns `429 Too Many Requests`.

```
bucker4j-bluetape4k-webflux bucket configuration example:
- Refill 10 tokens in one batch every 10 seconds (prevents bursts)
- Refill 10 tokens gradually every 1 minute (up to 100)
```

### Key-Based Separation

`bucker4j-bluetape4k-webflux` separates buckets by generating a unique key for each request.

| Key Strategy | Class | Description |
|---|---|---|
| User token | `UserKeyResolver` | Based on the Authorization header or token |
| IP address | IP-based KeyResolver | Separates buckets by client IP |

### WebFilter Flow

![WebFilter diagram](../docs/images/readme-diagrams/ratelimit-diagram-02.png)

The remaining token count is delivered to the client through the `X-Bluetape4k-Remaining-Token` response header.

## Running

```bash
# Redis required (Docker)
docker run -d -p 6379:6379 redis

./gradlew :bucker4j-bluetape4k-webflux:bootRun
./gradlew :bucket4j-redis:bootRun
./gradlew :bucket4j-caffeine-web:bootRun
```
