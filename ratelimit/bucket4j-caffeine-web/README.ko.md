# Bucket4j와 Caffeine을 사용하는 Spring Boot WebMVC 데모

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Bucket4j와 Caffeine을 사용하는 Spring Boot WebMVC 데모**를 실행 가능한 rate limiting 워크숍 조각으로 다룹니다. 개발자가 가장 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API를 중심으로 설명합니다.

## 아키텍처 다이어그램

![Spring Boot WebMVC with Bucket4j and Caffeine Demo architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-diagram-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.ratelimit` 패키지를 기준으로 삼습니다.

![Spring Boot WebMVC with Bucket4j and Caffeine Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-readme-architecture-01.png)

## 흐름 다이어그램

1. `ratelimit-bucket4j-caffeine-web`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 이미지가 있으면 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![Spring Boot WebMVC with Bucket4j and Caffeine Demo sequence diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-sequence-01.png)

## 아키텍처 다이어그램

![bucket4j caffeine web Architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-diagram-01.png)

이 프로젝트는 Caffeine을 Bucket4j 저장소로 사용하는 Spring Boot WebMVC 데모입니다.
Caffeine JCache는 동기 접근만 지원하므로 Spring Boot WebMVC에만 적합합니다.

Spring WebFlux 같은 비동기 API에서는 Redis, Hazelcast 또는 다른 비동기 지원 저장소를 사용하세요.
또 다른 선택지는 Virtual Threads 사용을 검토하는 것입니다.

## Rate Limit 요청 흐름

![Rate Limit diagram](../../docs/images/readme-diagrams/ratelimit-bucket4j-caffeine-web-sequence-01.png)

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
      url: .*                      # Apply to all URLs
      rate-limits:
        - bandwidths:
            - capacity: 10         # Maximum number of bucket tokens
              refill-capacity: 1   # Tokens refilled each interval
              time: 1
              unit: seconds
              initial-capacity: 20 # Initial token count (allows burst)
              refill-speed: interval
```

## 핵심 구성 요소

| 클래스 / 파일 | 역할 |
|---------------|------|
| `CaffeineApplication.kt` | Spring Boot 진입점, `@SpringBootApplication` |
| `IndexController.kt` | `GET /hello`와 `GET /world` 엔드포인트를 제공합니다 |
| `application.yml` | Caffeine JCache + Bucket4j filter 설정입니다 |
| `ServletRateLimitTest.kt` | `@SpringBootTest` 기반 Rate Limit 통합 테스트입니다 |

## 제약과 대안

| 항목 | 세부 사항 |
|------|------|
| 저장소 | Caffeine JCache — **동기(blocking)** 전용 |
| 적용 가능한 서버 | Spring Boot WebMVC(Servlet 기반) |
| 비동기 대안 | Redis(`LettuceBasedProxyManager`), Hazelcast |
| Virtual Threads 대안 | `spring.threads.virtual.enabled=true` + WebMVC |

## 빌드와 테스트

```bash
./gradlew :bucket4j-caffeine-web:test
```
