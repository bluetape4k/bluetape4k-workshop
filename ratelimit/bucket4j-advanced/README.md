# bucket4j-advanced - Advanced Rate Limit Strategies

[한국어](README.ko.md) | English

`bucket4j-advanced` demonstrates three independent Bucket4j rate-limit strategies on Spring
WebFlux coroutine filters. Each filter owns one path prefix, one identity key shape, and one bucket
configuration. All buckets are stored through the same Redis/Lettuce proxy manager.

## Architecture

![bucket4j-advanced architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-advanced-readme-architecture-01.png)

The filters are isolated by path prefix: anonymous traffic uses an IP bucket, authenticated traffic
uses `X-User-ID`, and sensitive traffic combines IP and user ID. Rate-limit failures return standard
headers; internal limiter errors fail open so the example endpoint remains available.

## Strategy Map

![bucket4j-advanced strategy map](../../docs/images/readme-diagrams/ratelimit-bucket4j-advanced-readme-strategy-map-01.png)

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
