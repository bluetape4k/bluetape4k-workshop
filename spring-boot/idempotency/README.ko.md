# spring-boot/idempotency

[English](README.md) | 한국어

이 모듈은 **Idempotency-Key** 헤더로 중복 주문 생성을 막는 WebFlux 예제입니다.
첫 `POST /api/orders` 요청의 응답을 Redisson `RMapCache`에 5분 동안 저장하고,
같은 key로 재시도하면 새 주문을 만들지 않고 원래 응답 body를 그대로 반환합니다.

## 아키텍처

![spring-boot/idempotency architecture diagram](../../docs/images/readme-diagrams/spring-boot-idempotency-readme-architecture-01.png)

## 요청 흐름

![spring-boot/idempotency request sequence diagram](../../docs/images/readme-diagrams/spring-boot-idempotency-sequence-01.png)

## 핵심 기능

| 기능 | 상세 |
|---|---|
| **중복 감지** | `Idempotency-Key` request header를 Redis cache와 매칭 |
| **Storage backend** | Redisson `RMapCache` — atomic `putIfAbsent`(SET NX semantics) |
| **TTL** | key당 5분 |
| **Concurrency** | `putIfAbsent`로 first writer wins; 늦은 concurrent writer는 cached response 수신 |
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

`RMapCache.putIfAbsent`는 check-and-set을 원자적으로 결합하므로 별도의 lock이나 Lua script가 필요 없습니다.

## 사용법

### 애플리케이션 시작

```bash
./gradlew :spring-boot-idempotency:bootRun
```

내장 `RedisServer.Launcher.redis` Testcontainer가 자동으로 시작됩니다.

### 요청 예시

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

### Idempotency-Key 없음 → 400

```bash
http POST :8080/api/orders productId=prod-001 quantity:=2 userId=user-123
# HTTP/1.1 400 Bad Request
```

## 설정

| 속성 | 기본값 | 설명 |
|---|---|---|
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |

## 테스트 실행

```bash
./gradlew :spring-boot-idempotency:test
```

테스트는 port binding을 위해 `@DynamicPropertySource`와 함께 `RedisServer.Launcher.redis` singleton Testcontainer를 사용합니다.

## 의존성

- `bluetape4k-redis` / `bluetape4k-redisson` — Redisson client helpers
- `bluetape4k-testcontainers` — `RedisServer.Launcher` singleton
- `bluetape4k-coroutines` — coroutine utilities
- `spring-boot-starter-webflux` — reactive HTTP + coroutine support

## 알려진 제한사항 / 향후 개선

- **Body-hash mismatch detection**: 이 모듈은 같은 key가 다른 request body와 함께 제출되는 경우를 감지하지 않습니다. 완전한 구현이라면 이 경우 422 Unprocessable Entity를 반환합니다.
- **Concurrent in-flight policy**: concurrent first-time request는 `putIfAbsent`로 처리됩니다. 409를 반환하지 않으며, loser는 winner의 cached response를 받습니다.
