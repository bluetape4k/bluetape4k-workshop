# spring-boot/idempotency

[한국어](README.ko.md) | English

This module shows duplicate-safe order creation with the **Idempotency-Key** header.
The first successful `POST /api/orders` stores its response in a Redisson `RMapCache`
for 5 minutes; retries with the same key return the original response body instead
of creating another order.

## Architecture

![spring-boot/idempotency architecture diagram](../../docs/images/readme-diagrams/spring-boot-idempotency-readme-architecture-01.png)

## Request Flow

![spring-boot/idempotency request flow diagram](../../docs/images/readme-diagrams/spring-boot-idempotency-sequence-01.png)

## Core Features

| Feature | Detail |
|---|---|
| **Duplicate detection** | `Idempotency-Key` request header matched against Redis cache |
| **Storage backend** | Redisson `RMapCache` — atomic `putIfAbsent` (SET NX semantics) |
| **TTL** | 5 minutes per key |
| **Concurrency** | First writer wins via `putIfAbsent`; concurrent late writers receive the cached response |
| **Missing header** | HTTP 400 Bad Request |
| **First creation** | HTTP 201 Created |
| **Replay** | HTTP 200 OK with original response body |

## Before / After

### Before — Raw Redis SET NX (Lettuce)

```kotlin
// Low-level approach: manual serialization, no TTL refresh, no type safety
val commands: RedisCommands<String, String> = ...
val json = objectMapper.writeValueAsString(response)
val result = commands.set(key, json, SetArgs().nx().ex(300))
if (result == null) {
    // key already existed — deserialize cached value
    val cached = objectMapper.readValue(commands.get(key), OrderResponse::class.java)
    return ResponseEntity.ok(cached)
}
return ResponseEntity.status(201).body(response)
```

### After — bluetape4k Redisson `RMapCache` (this module)

```kotlin
// High-level approach: type-safe, TTL managed automatically
val cache = redisson.getMapCache<String, CachedResponse>("idempotency:orders")
val previous = cache.putIfAbsent(key, newCached, 5, TimeUnit.MINUTES)
return if (previous == null) IdempotencyResult.Created(newCached)
       else IdempotencyResult.Replay(previous)
```

`RMapCache.putIfAbsent` combines the check-and-set atomically; no separate lock or Lua script needed.

## Usage

### Start the application

```bash
./gradlew :spring-boot-idempotency:bootRun
```

The embedded `RedisServer.Launcher.redis` Testcontainer starts automatically.

### Example request

```bash
# First call → 201 Created
http POST :8080/api/orders \
  "Idempotency-Key: $(uuidgen)" \
  productId=prod-001 quantity:=2 userId=user-123

# Retry with same key → 200 OK, identical orderId
http POST :8080/api/orders \
  "Idempotency-Key: <same-uuid>" \
  productId=prod-001 quantity:=2 userId=user-123
```

### Without Idempotency-Key → 400

```bash
http POST :8080/api/orders productId=prod-001 quantity:=2 userId=user-123
# HTTP/1.1 400 Bad Request
```

## Configuration

| Property | Default | Description |
|---|---|---|
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |

## Running Tests

```bash
./gradlew :spring-boot-idempotency:test
```

Tests use `RedisServer.Launcher.redis` singleton Testcontainer with `@DynamicPropertySource` for port binding.

## Dependencies

- `bluetape4k-redis` / `bluetape4k-redisson` — Redisson client helpers
- `bluetape4k-testcontainers` — `RedisServer.Launcher` singleton
- `bluetape4k-coroutines` — coroutine utilities
- `spring-boot-starter-webflux` — reactive HTTP + coroutine support

## Known Limitations / Future Enhancements

- **Body-hash mismatch detection**: this module does not detect when the same key is submitted with a different request body (a full implementation would return 422 Unprocessable Entity in that case).
- **Concurrent in-flight policy**: concurrent first-time requests resolve via `putIfAbsent`; no 409 is returned — the loser simply receives the winner's cached response.
