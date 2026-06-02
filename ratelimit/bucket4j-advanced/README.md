# bucket4j-advanced — Advanced Rate Limit Strategies

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **bucket4j-advanced — Advanced Rate Limit Strategies** as a runnable rate limiting workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `ratelimit-bucket4j-advanced`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

A Spring Boot WebFlux + Coroutines workshop module demonstrating three distinct Bucket4j
rate-limit identity strategies backed by Redis (Lettuce).

## Architecture

![bucket4j advanced Architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-advanced-architecture-01.png)

## Rate-Limit Strategies

| Strategy | Endpoint | Key | Bucket | Header required |
|---|---|---|---|---|
| IP-based | `GET /api/anonymous/hello` | `ip:<address>` | 20 tokens / 10 s | none |
| userId-based | `GET /api/authenticated/hello` | `user:<userId>` | 50 tokens / 10 s | `X-User-ID` |
| Combined (IP + userId) | `GET /api/sensitive/hello` | `combined:<ip>:<userId>` | 10 tokens / 10 s | `X-User-ID` |

## Before / After Comparison

| Concern | Before (basic `bucker4j-bluetape4k-webflux`) | After (this module) |
|---|---|---|
| Identity strategies | Single strategy (userId or IP) | Three independent strategies |
| Filter isolation | Two overlapping filters caused double-counting | Each filter only handles its own path prefix |
| Response headers | `X-BLUETAPE4K-REMAINING-TOKEN` (custom) | `X-RateLimit-Remaining` + `Retry-After` (HTTP standard) |
| User ID header | `X-BLUETAPE4K-UID` | `X-User-ID` |
| Missing identity | Falls back to remoteAddress silently | Explicit error codes (400 / 401) |
| Combined quota | Not supported | IP+userId composite key |

## Response Headers

| Header | Meaning |
|---|---|
| `X-RateLimit-Remaining` | Tokens remaining in the current window after this request |
| `X-RateLimit-Reset` | _(reserved — not yet populated)_ |
| `Retry-After` | Seconds to wait before retrying; present only on HTTP 429 responses |

## Proxy Trust Configuration

By default `ratelimit.trust-proxy=false` and the `X-Forwarded-For` / `X-Real-IP` headers are
**ignored**. The raw TCP remote address is always used for IP extraction.

**Security warning**: setting `ratelimit.trust-proxy=true` trusts `X-Forwarded-For` and uses the
**leftmost** IP from that header as the client address. Enable this only when the application is
behind a known, controlled reverse proxy (e.g. an internal load balancer). Without a trusted
proxy, clients can spoof arbitrary IP addresses in the `X-Forwarded-For` header and bypass
per-IP rate limits entirely.

```yaml
# application.yml — enable only behind a trusted proxy
ratelimit:
  trust-proxy: true
```

## Running

```bash
# Start a Redis instance (or let Testcontainers manage it in dev/test profile)
./gradlew :bucket4j-advanced:bootRun

# Run tests
./gradlew :bucket4j-advanced:test
```

## Example Requests

```bash
# IP-based (anonymous)
curl http://localhost:8080/api/anonymous/hello

# userId-based (authenticated)
curl -H "X-User-ID: alice" http://localhost:8080/api/authenticated/hello

# Combined IP + userId (sensitive)
curl -H "X-User-ID: alice" http://localhost:8080/api/sensitive/hello
```

## Dependencies

| Dependency | Purpose |
|---|---|
| `bluetape4k-bucket4j` | `DistributedSuspendRateLimiter`, `AsyncBucketProxyProvider`, `bucketConfiguration {}` DSL |
| `bucket4j-lettuce` | Redis-backed distributed `ProxyManager` |
| `bluetape4k-redis` / `lettuce-core` | Lettuce Redis client |
| `bluetape4k-coroutines` | `mono {}`, `awaitSingleOrNull()` bridges |
| `bluetape4k-testcontainers` | `RedisServer.Launcher.redis` singleton for tests |
