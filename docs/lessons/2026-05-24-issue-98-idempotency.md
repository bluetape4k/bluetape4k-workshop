# Issue #98 — Idempotency Key Workshop 구현 회고

날짜: 2026-05-24

## 목표

중복 요청 방지를 위한 Idempotency Key 패턴을 `spring-boot/idempotency` 모듈로 구현.

## 구현 선택

### 저장소: Redisson `RMapCache` 선택

| 옵션 | 장점 | 단점 |
|---|---|---|
| Raw Lettuce SET NX | 심플, 저수준 제어 | 수동 직렬화, TTL 갱신 어려움, 타입 안전성 없음 |
| Redisson `RMapCache` | 원자적 putIfAbsent, TTL 자동 관리, 타입 안전, Kryo/Fory 코덱 지원 | Redisson 의존성 추가 |

`RMapCache.putIfAbsent(key, value, ttl, unit)`은 Redis `SET NX EX`를 한 번의 원자적 호출로 처리.

### 동시성 정책: `putIfAbsent` 승자 우선

동시에 동일 키로 첫 요청이 들어오는 경우 Redis의 `putIfAbsent`가 단일 승자를 결정. 패자는 승자가 저장한 응답을 즉시 반환받음(409 없음). 워크샵 목적에 충분.

### HTTP 상태 코드 전략

- **최초 생성**: 201 Created + 응답 본문 저장
- **재전송(replay)**: 200 OK + 동일 응답 본문
- 봉투(`CachedResponse`)에 원본 HTTP 상태 코드 + 응답 페이로드 함께 저장

## 테스트 패턴

- `AbstractIdempotencyTest`: `@DynamicPropertySource`로 Testcontainer Redis 포트 바인딩
- `RedisServer.Launcher.redis` 싱글톤 패턴: 모든 테스트가 단일 컨테이너 공유
- `WebEnvironment.RANDOM_PORT`: 포트 충돌 없이 독립 실행

## 알게 된 것

1. **`RMapCache.putIfAbsent`** 는 별도 분산 락 없이 체크-앤-셋을 원자적으로 수행. 단순한 이멱등성 시나리오에서는 이것으로 충분.
2. **`data class` + `Serializable` + `serialVersionUID`** : bluetape4k 규칙 — 모든 `data class`에 필수. `OrderRequest`, `OrderResponse`, `CachedResponse` 모두 적용.
3. **`@DynamicPropertySource`** 는 `companion object` 안에 `@JvmStatic`과 함께 선언해야 Spring이 인식.
4. **Spring Boot WebFlux + Coroutines** : `suspend` 함수를 `@PostMapping` 핸들러로 바로 사용 가능. `CancellationException` 재던지기 주의.
5. **헤더 검증은 컨트롤러에서**: `required = false`로 받아 `isNullOrBlank()` 체크 후 `ResponseStatusException(400)` 던짐.

## 향후 개선 사항

- 동일 키 + 다른 본문 → 422 Unprocessable Entity (body hash 비교)
- Idempotency-Key TTL 설정 가능하게 (현재 5분 하드코딩)
- 분산 환경 테스트 (Redis Cluster)
