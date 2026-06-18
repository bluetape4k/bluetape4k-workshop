# Spring Cloud Gateway Sample

[한국어](README.ko.md) | English

## What this example shows

This module shows how Spring Cloud Gateway composes route predicates and filters in Kotlin: path routing,
host routing, rewrite filters, Resilience4j circuit breaker fallback, Redis-backed request rate limiting, and
a simple WebSocket proxy route.

## Architecture Diagram

![Spring Cloud Gateway route architecture](../../docs/images/readme-diagrams/spring-cloud-gateway-example-readme-architecture-01.png)

The architecture separates route matching, filter behavior, the upstream target, and stateful rate-limit storage.

## Route Flow

![Spring Cloud Gateway route flow](../../docs/images/readme-diagrams/spring-cloud-gateway-example-readme-flow-01.png)

## Notes

This example does not use Bucket4j. It demonstrates Spring Cloud's built-in
Redis-backed Rate Limiter and Resilience4j Circuit Breaker.

## Routes

| Route | Match | Filters | Target |
|---|---|---|---|
| `path_route` | `GET /get` | `prefixPath("/httpbin")` | `https://nghttp2.org/` |
| `host_route` | `Host: *.myhost.org` | `prefixPath("/httpbin")` | `https://nghttp2.org/` |
| `rewrite_route` | `Host: *.rewrite.org` | prefix + `/foo/{segment}` rewrite | `https://nghttp2.org/` |
| `circuitbreaker_route` | `Host: *.circuitbreaker.org` | Resilience4j circuit breaker `slowcmd` | `https://nghttp2.org/` |
| `circuitbreaker_fallback_route` | `Host: *.circuitbreakerfallback.org` | circuit breaker + `forward:/circuitbreaker/fallback` | local fallback |
| `limit_route` | `Host: *.limited.org` and `/anything/**` | Redis `RequestRateLimiter` with `UserKeyResolver` | `https://nghttp2.org/` |
| `websocket_route` | `/echo` | WebSocket proxy | `ws://localhost:9000` |

## HTTP Samples

```bash
http :8080/get
http :8080/headers Host:www.myhost.org
http :8080/foo/get Host:www.rewrite.org
http :8080/delay/3 Host:www.circuitbreakerfallback.org
http :8080/anything/1 Host:www.limited.org X-BLUETAPE4K-UID:user-a
```

## Websocket Sample

[install wscat](https://www.npmjs.com/package/wscat)

In one terminal, run websocket server:

```
wscat --listen 9000
``` 

In another, run a client, connecting through gateway:

```
wscat --connect ws://localhost:8080/echo
```

type away in either server and client, messages will be passed appropriately.

## Running Redis Rate Limiter Test

Make sure redis is running on localhost:6379 (using brew or apt or docker).

Then run `DemogatewayApplicationTests`. It should pass which means one of the calls received a 429 TO_MANY_REQUESTS HTTP
status.

## Resources

- [Spring Cloud Gateway with Resilience4j circuit breaker](https://medium.com/@mahmoud.romeh/spring-cloud-gateway-with-resilience4j-circuit-breaker-4f46d86822f0)
- [spring-cloud-gateway sample](https://github.com/m-thirumal/spring-cloud-gateway)
