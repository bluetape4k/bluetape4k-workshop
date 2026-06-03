# spring-boot/idempotency

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **spring-boot/idempotency** 모듈을 실행 가능한 Spring Boot 애플리케이션 기능 워크샵 조각으로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 흐름 다이어그램

1. `spring-boot-idempotency`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![spring-boot/idempotency sequence diagram](../../docs/images/readme-diagrams/spring-boot-idempotency-sequence-01.png)

Redis(Redisson)와 Spring Boot WebFlux + Kotlin Coroutines를 사용해 **Idempotency Key** 패턴으로 duplicate-safe command handling을 구현합니다.

## 아키텍처

![spring-boot/idempotency Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-idempotency-readme-architecture-01.png)

![idempotency Sequence Flow diagram](../../docs/images/readme-diagrams/spring-boot-idempotency-sequence-01.png)

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
