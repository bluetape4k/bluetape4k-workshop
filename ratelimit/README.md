# Rate Limiter 예제

## 서브모듈 구조

```mermaid
flowchart TD
    subgraph ratelimit 모듈
        BW[bucker4j-bluetape4k-webflux\n사용자 토큰 기반 / Redis / WebFlux\n추천]
        BR[bucket4j-redis\nIP 기반 / Redis / WebFlux\nbucket4j-spring-boot-starter]
        BC[bucket4j-caffeine-web\nIP 기반 / Caffeine / WebMVC\n로컬 전용]
    end

    subgraph 저장소
        R[(Redis)]
        CAF[(Caffeine\n인메모리)]
    end

    subgraph 클라이언트
        CLI[HTTP 클라이언트]
    end

    CLI --> BW
    CLI --> BR
    CLI --> BC
    BW --> R
    BR --> R
    BC --> CAF
```

## bucket4j-bluetape4k-webflux (추천)

`bluetape4k-bucket4j` 에서 제공하는 사용자 토큰 기반의 RateLimiter 를 사용한 예제입니다.

IP 기반, 사용자 토큰 기반 모두 제공하고, Redis 를 Bucket 저장소로 사용합니다.

## bucket4j-caffeine-web

`bucket4j-spring-boot-starter` 를 사용한 예제입니다. IP 기반 Rate Limiter를 제공하고, 로컬용입니다.

## bucket4j-redis

`bucket4j-spring-boot-starter` 를 사용한 예제입니다. IP 기반 Rate Limiter를 제공

## 모듈별 비교

| 항목 | `bucker4j-bluetape4k-webflux` | `bucket4j-redis` | `bucket4j-caffeine-web` |
|---|---|---|---|
| **추천 여부** | 추천 | 보통 | 로컬/개발용 |
| **식별 기준** | 사용자 토큰 / IP | IP | IP |
| **저장소** | Redis (Lettuce) | Redis (Lettuce) | Caffeine (인메모리) |
| **스택** | WebFlux + 코루틴 | WebFlux + 코루틴 | WebMVC (서블릿) |
| **분산 지원** | 가능 | 가능 | 불가 (단일 노드) |
| **라이브러리** | `bluetape4k-bucket4j` | `bucket4j-spring-boot-starter` | `bucket4j-spring-boot-starter` |

## Rate Limit 전략

### Token Bucket 알고리즘

Bucket4j는 Token Bucket 알고리즘을 사용합니다. 버킷에 토큰이 남아 있으면 요청을 허용하고, 소진되면 `429 Too Many Requests`를 반환합니다.

```
bucker4j-bluetape4k-webflux 버킷 설정 예시:
- 10초마다 10개 토큰 일괄 충전 (burst 방지)
- 1분마다 10개씩 점진적 충전 (최대 100개)
```

### 키 기반 분리

`bucker4j-bluetape4k-webflux`는 요청별로 고유한 키를 생성하여 버킷을 분리합니다.

| 키 전략 | 클래스 | 설명 |
|---|---|---|
| 사용자 토큰 | `UserKeyResolver` | Authorization 헤더 또는 토큰 기반 |
| IP 주소 | IP 기반 KeyResolver | 클라이언트 IP로 버킷 구분 |

### WebFilter 동작 흐름

```mermaid
flowchart LR
    요청 -->|모든 HTTP 요청| UserRateLimitWebFilter

    subgraph UserRateLimitWebFilter
        T{대상 경로?}
        T -->|아님| PASS[다음 필터]
        T -->|맞음| KEY[키 추출]
        KEY --> CONSUME[토큰 소비]
        CONSUME -->|토큰 있음| PASS
        CONSUME -->|토큰 없음| REJECT[429 응답]
    end

    PASS --> Controller
```

응답 헤더 `X-Bluetape4k-Remaining-Token`으로 남은 토큰 수를 클라이언트에 전달합니다.

## 실행 방법

```bash
# Redis 필요 (Docker)
docker run -d -p 6379:6379 redis

./gradlew :bucker4j-bluetape4k-webflux:bootRun
./gradlew :bucket4j-redis:bootRun
./gradlew :bucket4j-caffeine-web:bootRun
```
