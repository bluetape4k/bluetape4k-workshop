# Rate Limiter 예제

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Rate Limiter 예제**를 실행 가능한 rate limiting 워크숍 조각으로 다룹니다. 개발자가 가장 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API를 중심으로 설명합니다.

## 아키텍처 다이어그램

![Rate Limiter Examples Graphviz architecture diagram](../docs/images/readme-diagrams/ratelimit-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.ratelimit` 패키지를 기준으로 삼습니다.

![Rate Limiter Examples architecture diagram](../docs/images/readme-diagrams/ratelimit-bucket4j-advanced-architecture-01.png)

## 흐름 다이어그램

1. `Rate Limiter Examples`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

![Rate Limiter Examples flow diagram](../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-diagram-01.png)

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 이미지가 있으면 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![Rate Limiter Examples sequence diagram](../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-sequence-01.png)

## 서브모듈 구조

![ratelimit Architecture diagram](../docs/images/readme-diagrams/ratelimit-diagram-01.png)

## bucket4j-bluetape4k-webflux(권장)

이 예제는 `bluetape4k-bucket4j`가 제공하는 사용자 토큰 기반 RateLimiter를 사용합니다.

IP 기반과 사용자 토큰 기반 rate limiting을 모두 지원하며, Redis를 bucket store로 사용합니다.

## bucket4j-caffeine-web

이 예제는 `bucket4j-spring-boot-starter`를 사용합니다. IP 기반 Rate Limiter를 제공하며 로컬 사용을 목적으로 합니다.

## bucket4j-redis

이 예제는 `bucket4j-spring-boot-starter`를 사용합니다. IP 기반 Rate Limiter를 제공합니다.

## 모듈 비교

| 항목 | `bucker4j-bluetape4k-webflux` | `bucket4j-redis` | `bucket4j-caffeine-web` |
|---|---|---|---|
| **권장 여부** | 권장 | 표준 | 로컬/개발용 |
| **식별 기준** | 사용자 토큰 / IP | IP | IP |
| **저장소** | Redis(Lettuce) | Redis(Lettuce) | Caffeine(인메모리) |
| **스택** | WebFlux + coroutines | WebFlux + coroutines | WebMVC(Servlet) |
| **분산 지원** | 예 | 예 | 아니요(단일 노드) |
| **라이브러리** | `bluetape4k-bucket4j` | `bucket4j-spring-boot-starter` | `bucket4j-spring-boot-starter` |

## Rate Limit 전략

### Token Bucket 알고리즘

Bucket4j는 Token Bucket 알고리즘을 사용합니다. bucket에 토큰이 남아 있으면 요청을 허용하고, 토큰이 고갈되면 `429 Too Many Requests`를 반환합니다.

```
bucker4j-bluetape4k-webflux bucket configuration example:
- Refill 10 tokens in one batch every 10 seconds (prevents bursts)
- Refill 10 tokens gradually every 1 minute (up to 100)
```

### Key 기반 분리

`bucker4j-bluetape4k-webflux`는 요청마다 고유 key를 생성해 bucket을 분리합니다.

| Key 전략 | 클래스 | 설명 |
|---|---|---|
| 사용자 토큰 | `UserKeyResolver` | Authorization 헤더 또는 토큰을 기준으로 합니다 |
| IP 주소 | IP 기반 KeyResolver | 클라이언트 IP별로 bucket을 분리합니다 |

### WebFilter 흐름

![WebFilter diagram](../docs/images/readme-diagrams/ratelimit-diagram-02.png)

남은 토큰 수는 `X-Bluetape4k-Remaining-Token` 응답 헤더를 통해 클라이언트에 전달됩니다.

## 실행

```bash
# Redis required (Docker)
docker run -d -p 6379:6379 redis

./gradlew :bucker4j-bluetape4k-webflux:bootRun
./gradlew :bucket4j-redis:bootRun
./gradlew :bucket4j-caffeine-web:bootRun
```
