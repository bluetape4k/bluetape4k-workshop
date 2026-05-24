# Lessons: cache-resilience module with ToxiproxyServer

**Date**: 2026-05-24  
**Module**: `spring-boot/cache-resilience`  
**PR**: #187

## Root cause / context

Issue #84 requested Spring Boot Advanced examples including "Redis failure paths + CircuitBreaker fallback".
The goal was to show the full CB state machine (CLOSED→OPEN→HALF-OPEN→CLOSED) driven by real network
failures — without mocking Redis.

## Key decisions

### 1. ToxiproxyServer for chaos injection

`bluetape4k-testcontainers` exposes `ToxiproxyServer` wrapping the Shopify Toxiproxy container.
The proxy sits between the test's Lettuce client and the in-Docker Redis server:

```
Lettuce → toxiproxy (host:proxyPort) → redis (docker-network:6379)
```

All containers join a shared `Network.newNetwork()` so Redis is reachable via Docker alias `redis`.

### 2. `limitData(0)` vs `timeout(1ms)` — the one-shot pitfall

**First attempt**: `proxy.toxics().limitData("cut", ToxicDirection.DOWNSTREAM, 0)`  
**Problem**: `limitData` is a **one-shot toxic** — it fires once (after 0 bytes pass) and then auto-removes
itself. After the first connection drop, Lettuce reconnects; the toxic is already gone, so subsequent
calls succeed. CB never accumulated enough failures.

**Fix**: `proxy.toxics().timeout("drop-connections", ToxicDirection.UPSTREAM, 1)` — persistent toxic that
closes every new connection after 1 ms. CB sees repeated failures on every reconnect attempt.

### 3. Lettuce command timeout must be set explicitly

Lettuce's default command timeout is **60 seconds**. With 4 × 60s = 4 minutes of waiting, the test was
slow but ultimately had the wrong CB state because `limitData` was one-shot (see above).

After switching to `timeout(1ms)` toxic, the calls still took ~3s each because Lettuce's 60s
commandTimeout was the ceiling. Fix: set a short `commandTimeout`:

```kotlin
val clientConfig = LettuceClientConfiguration.builder()
    .commandTimeout(Duration.ofSeconds(3))
    .build()
factory = LettuceConnectionFactory(connectionConfig, clientConfig)
```

Result: 4 × 3s = 12s for the failure-injection phase → total test time 41s.

### 4. `Proxy.setEnabled(false)` does not exist in this toxiproxy-java version

Attempting to use `proxy.setEnabled(false)` caused a compile error — the method is not available in the
version of `eu.rekawek.toxiproxy:toxiproxy-java` bundled with `org.testcontainers:testcontainers-toxiproxy:2.0.5`.
Use toxics API (`timeout`, `bandwidth`, `limitData`) instead.

### 5. `ToxiproxyServer.withNetwork()` returns `ToxiproxyContainer`, not `ToxiproxyServer`

Builder chaining (`ToxiproxyServer().withNetwork(network)`) returns the parent type. Use `also {}`:

```kotlin
toxiproxyServer = ToxiproxyServer().also {
    it.withNetwork(network)
}
```

### 6. `testcontainers-toxiproxy` module name in 2.x

In testcontainers 2.x, the module was renamed from `org.testcontainers:toxiproxy` to
`org.testcontainers:testcontainers-toxiproxy`. Declare explicitly (not in catalog):

```kotlin
testImplementation("org.testcontainers:testcontainers-toxiproxy") {
    version { require(libs.versions.testcontainers.get()) }
}
```

## Outcome / verification

- 4 tests passing in 41 seconds:
  - happy path (CB CLOSED, Redis read)
  - failure injection (timeout toxic → CB OPEN → Caffeine fallback)
  - recovery (remove toxic → CB HALF-OPEN → CLOSED)
  - cache miss (null returned)
- Architecture diagram (SVG + PNG) committed
- README.md + README.ko.md with Used Bluetape4k Features table

## Future guidance

- When using Toxiproxy toxics, prefer **persistent** toxics (`timeout`, `bandwidth`) over one-shot toxics (`limitData`) for CB failure injection tests.
- Always set a short `commandTimeout` on the Lettuce client used in tests; the default 60s makes chaos tests very slow.
- `ToxiproxyServer` requires `testcontainers-toxiproxy` (2.x name) as an EXPLICIT test dependency because `bluetape4k-testcontainers` declares it `compileOnly`.
- Check the `toxiproxy-java` API version before using enable/disable methods; use toxics API instead.
