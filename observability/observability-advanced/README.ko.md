# observability-advanced

HTTP(WebFlux), 코루틴 서비스, H2 데이터베이스(Exposed JDBC), Redis 캐시에 걸쳐
다계층 스팬 계측을 보여주는 전체 스택 Observability 워크샵 예제.

## 아키텍처

![observability advanced Architecture diagram](../../docs/images/readme-diagrams/observability-observability-advanced-architecture-01.png)

## 스팬 트리

**캐시 미스 경로:**
```
http.server.requests              (자동)
  └─ user.service.get             (수동)
       ├─ user.cache.get          (수동 — null 반환)
       ├─ user.db.find            (수동)
       └─ user.cache.put          (수동)
```

**캐시 히트 경로:**
```
http.server.requests              (자동)
  └─ user.service.get             (수동)
       └─ user.cache.get          (수동 — User 반환)
```

## 핵심 개념

| 개념 | 구현 |
|------|------|
| 다계층 스팬 | 서비스 + 캐시 계층에 `observed()` 헬퍼 적용 |
| 디스패처 경계 | `withObservation { withContext(IO) { transaction { } } }` (Observation OUTER) |
| Redis Soft-fail | catch + log.warn, DB 폴백 |
| Cache-aside 패턴 | get → miss → DB → put |
| 긍정 테스트 어설션 | `TestObservationRegistryAssert.assertThat(testRegistry).hasObservationWithNameEqualTo(...)` |
| 부정 테스트 어설션 | `TestObservationRegistryAssert.assertThat(testRegistry).hasNumberOfObservationsWithNameEqualTo(name, 0)` |

## 테스트 커버리지

- `UserServiceTest`: 캐시 미스/히트 스팬, null 결과, 생성 스팬, 명시적 캐시 삭제로 DB 조회 강제
- `UserControllerTest`: HTTP POST 생성, GET 캐시 미스, GET 캐시 히트

## 의존성

- `bluetape4k-micrometer` — 로컬 `observed()` 코루틴 래퍼 (finally-safe)
- `bluetape4k-redisson` — `redissonClient {}` DSL (`io.bluetape4k.redis.redisson`)
- `micrometer-context-propagation` — 디스패처 경계 스팬 연속성
- `jetbrains-exposed-spring-boot4-starter` — Exposed 자동 구성
