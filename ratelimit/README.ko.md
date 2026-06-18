# Rate Limiter 예제

[English](README.md) | 한국어

이 디렉터리는 Bucket4j rate-limit 워크숍 모듈을 모아 둔 곳입니다. 먼저 어떤 identity model, storage model, Spring stack이 필요한지 고른 뒤 각 하위 모듈 README로 들어가면 됩니다.

## 모듈 지도

![Rate limiter module map](../docs/images/readme-diagrams/ratelimit-readme-module-map-01.png)

`bucker4j-bluetape4k-webflux`는 bluetape4k `DistributedRateLimiter`와 사용자 token 기반 bucket을 보고 싶을 때 권장 경로입니다. `bucket4j-advanced`는 IP, user, combined key filter를 비교하는 정책 실험 모듈입니다. `bucket4j-redis`는 Bucket4j starter 방식을 유지하면서 token 상태를 Redis에 저장합니다. `bucket4j-caffeine-web`은 단일 JVM에서 동작하는 로컬 WebMVC starter 예제입니다.

## 선택 흐름

![Rate limiter module selection flow](../docs/images/readme-diagrams/ratelimit-readme-selection-flow-01.png)

먼저 필요한 key를 고릅니다. 사용자 token이나 여러 identity 조합이 필요하면 bluetape4k 모듈을 보면 됩니다. IP-only starter 동작만 확인하면 Redis 또는 Caffeine starter 모듈이 맞습니다. Redis 예제는 공유 bucket 상태를, Caffeine 예제는 로컬 servlet filter 동작을 보여줍니다.

## 모듈

| Module | 볼 내용 | Stack | Store | Identity |
|---|---|---|---|---|
| `bucker4j-bluetape4k-webflux` | 권장 bluetape4k WebFlux limiter | WebFlux + coroutines | Redis (Lettuce) | User token 또는 IP |
| `bucket4j-advanced` | IP, user, combined-key 정책 비교 | WebFlux + coroutines | Redis (Lettuce) | IP, user, combined |
| `bucket4j-redis` | 분산 token 상태를 쓰는 Bucket4j starter | WebFlux + Reactor/coroutines | Redis (Lettuce) | URL/IP 중심 starter rule |
| `bucket4j-caffeine-web` | Redis 없는 로컬 starter 동작 | Spring WebMVC | Caffeine JCache | URL/IP 중심 starter rule |

## 공통 개념

모든 모듈은 token bucket 판단을 보여줍니다.

| 개념 | 의미 |
|---|---|
| Bucket capacity | 한 window에서 사용할 수 있는 최대 token 수 |
| Refill | Token이 bucket으로 돌아오는 방식과 주기 |
| Key resolver | Caller별 bucket을 분리하는 기준값 |
| Store | Bucket 상태 저장 위치: local Caffeine 또는 shared Redis |
| Exhausted bucket | Token이 없으면 `429 Too Many Requests`로 거절 |

bluetape4k WebFlux 예제는 모듈에 따라 `X-Bluetape4k-Remaining-Token` 또는 표준 `X-RateLimit-Remaining` / `Retry-After` response header도 보여줍니다.

## 실행

```bash
./gradlew :bucker4j-bluetape4k-webflux:bootRun
./gradlew :bucket4j-advanced:bootRun
./gradlew :bucket4j-redis:bootRun
./gradlew :bucket4j-caffeine-web:bootRun
```

Redis-backed 모듈의 테스트는 repository의 Testcontainers 기반 테스트를 사용합니다. 직접 실행할 때는 접근 가능한 Redis instance를 준비하고 각 모듈의 `application.yml` property에 맞추면 됩니다.
