# Spring Boot WebMVC with Bucket4j and Caffeine Demo

## 아키텍처 다이어그램

```mermaid
flowchart LR
    subgraph 클라이언트
        C[HTTP 클라이언트]
    end

    subgraph Spring Boot WebMVC
        RL[Rate Limit 필터\nbucket4j-spring-boot-starter]
        IC[IndexController\nGET /hello\nGET /world]
    end

    subgraph Bucket4j 토큰 버킷
        CAF[(Caffeine JCache\n인메모리 저장소)]
        BK[Bucket\n토큰 소비/충전]
    end

    C -->|HTTP 요청| RL
    RL -->|tryConsume| BK
    BK <-->|버킷 상태 관리| CAF
    BK -->|토큰 있음| IC
    BK -->|토큰 없음 429| C
    IC -->|응답| C
```

Bucket4j 저장소로 Caffeine 을 사용하는 Spring Boot WebMVC 데모 프로젝트입니다.
Caffeine JCache 가 동기 방식밖에 지원하지 않기 때문에 Spring Boot WebMVC 에서만 가능합니다.

만약, Spring Webflux 등 비동기 방식의 API 에 대해서는 Redis, Hazelcast 등을 사용해야 합니다.
아니면 Virtual Threads 를 사용하는 방식을 고려해야 합니다.

## Rate Limit 요청 처리 흐름

```mermaid
sequenceDiagram
    participant C as HTTP 클라이언트
    participant F as Bucket4j 필터
    participant CAF as Caffeine JCache
    participant IC as IndexController

    C->>F: GET /hello (또는 /world)
    F->>CAF: 버킷 상태 조회 (IP 키)
    CAF-->>F: 현재 토큰 수 반환
    alt 토큰 있음
        F->>CAF: tryConsume(1) → 토큰 차감
        F->>IC: 요청 전달
        IC-->>C: 200 OK + 응답 본문
    else 토큰 없음 (초과)
        F-->>C: 429 Too Many Requests
    end
```

## application.yml 설정 예제

```yaml
spring:
  cache:
    jcache:
      provider: com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider
    cache-names:
      - buckets
    caffeine:
      spec: maximumSize=1000000,expireAfterAccess=3600s

bucket4j:
  enabled: true
  filters:
    - cache-name: buckets
      url: .*                      # 모든 URL 에 적용
      rate-limits:
        - bandwidths:
            - capacity: 10         # 버킷 최대 토큰 수
              refill-capacity: 1   # 매 interval 마다 충전 토큰 수
              time: 1
              unit: seconds
              initial-capacity: 20 # 초기 토큰 수 (burst 허용)
              refill-speed: interval
```

## 주요 구성 요소

| 클래스 / 파일 | 역할 |
|---------------|------|
| `CaffeineApplication.kt` | Spring Boot 진입점, `@SpringBootApplication` |
| `IndexController.kt` | `GET /hello`, `GET /world` 엔드포인트 제공 |
| `application.yml` | Caffeine JCache + Bucket4j 필터 설정 |
| `ServletRateLimitTest.kt` | `@SpringBootTest` 기반 Rate Limit 통합 테스트 |

## 제약 사항 및 대안

| 항목 | 내용 |
|------|------|
| 저장소 | Caffeine JCache — **동기(Blocking)** 전용 |
| 적용 가능 서버 | Spring Boot WebMVC (Servlet 기반) |
| 비동기 대안 | Redis (`LettuceBasedProxyManager`), Hazelcast |
| Virtual Threads 대안 | `spring.threads.virtual.enabled=true` + WebMVC |

## 빌드 및 테스트

```bash
./gradlew :bucket4j-caffeine-web:test
```
