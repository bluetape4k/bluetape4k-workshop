# spring-boot/idempotency

**Idempotency Key** 패턴을 이용한 중복 요청 방지 예제. Redis(Redisson)와 Spring Boot WebFlux + Kotlin 코루틴을 사용합니다.

## 아키텍처

![idempotency Sequence Flow diagram](../../docs/images/readme-diagrams/spring-boot-idempotency-sequence-01.png)

## 주요 기능

| 기능 | 설명 |
|---|---|
| **중복 감지** | `Idempotency-Key` 요청 헤더를 Redis 캐시와 비교 |
| **저장 백엔드** | Redisson `RMapCache` — 원자적 `putIfAbsent` (SET NX 의미론) |
| **TTL** | 키당 5분 |
| **동시성** | `putIfAbsent`로 첫 번째 작성자가 우선; 후속 동시 요청은 캐시된 응답 수신 |
| **헤더 없음** | HTTP 400 Bad Request |
| **최초 생성** | HTTP 201 Created |
| **재전송** | HTTP 200 OK + 원본 응답 본문 |

## Before / After 비교

### Before — Lettuce로 직접 Redis SET NX

```kotlin
// 저수준 접근: 수동 직렬화, TTL 관리 없음, 타입 안전성 없음
val result = commands.set(key, json, SetArgs().nx().ex(300))
if (result == null) {
    val cached = objectMapper.readValue(commands.get(key), OrderResponse::class.java)
    return ResponseEntity.ok(cached)
}
return ResponseEntity.status(201).body(response)
```

### After — bluetape4k Redisson `RMapCache` (이 모듈)

```kotlin
// 고수준 접근: 타입 안전, TTL 자동 관리
val cache = redisson.getMapCache<String, CachedResponse>("idempotency:orders")
val previous = cache.putIfAbsent(key, newCached, 5, TimeUnit.MINUTES)
return if (previous == null) IdempotencyResult.Created(newCached)
       else IdempotencyResult.Replay(previous)
```

`RMapCache.putIfAbsent`가 체크-앤-셋을 원자적으로 처리하므로 별도의 락이나 Lua 스크립트가 필요 없습니다.

## 사용법

### 애플리케이션 시작

```bash
./gradlew :spring-boot-idempotency:bootRun
```

`RedisServer.Launcher.redis` Testcontainer가 자동으로 시작됩니다.

### 요청 예시

```bash
# 최초 요청 → 201 Created
http POST :8080/api/orders \
  "Idempotency-Key: $(uuidgen)" \
  productId=prod-001 quantity:=2 userId=user-123

# 동일 키로 재전송 → 200 OK, 동일한 orderId
http POST :8080/api/orders \
  "Idempotency-Key: <동일-uuid>" \
  productId=prod-001 quantity:=2 userId=user-123
```

### Idempotency-Key 없음 → 400

```bash
http POST :8080/api/orders productId=prod-001 quantity:=2 userId=user-123
# HTTP/1.1 400 Bad Request
```

## 테스트 실행

```bash
./gradlew :spring-boot-idempotency:test
```

`RedisServer.Launcher.redis` 싱글톤 Testcontainer와 `@DynamicPropertySource`를 사용합니다.

## 알려진 제한사항 / 향후 개선

- **요청 본문 해시 검증 미구현**: 동일 키로 다른 본문 전송 시 422를 반환하지 않음.
- **동시 최초 요청 정책**: `putIfAbsent`로 처리; 패자는 즉시 캐시된 응답을 받습니다(409 없음).
