# Spring WebFlux에서 Bucket4j로 사용자별 Rate Limit 적용

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Webflux에서 Bucket4j로 사용자별 Rate Limit 적용**을 실행 가능한 rate limiting 워크숍 조각으로 다룹니다. 개발자가 가장 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API를 중심으로 설명합니다.

## 아키텍처 다이어그램

![Rate Limit per user with Bucket4j in Spring Webflux architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-diagram-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.ratelimit` 패키지를 기준으로 삼습니다.

![Rate Limit per user with Bucket4j in Spring Webflux Graphviz architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-readme-architecture-01.png)

## 흐름 다이어그램

1. `ratelimit-bucker4j-bluetape4k-webflux`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 이미지가 있으면 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

## 아키텍처 다이어그램

![bucker4j bluetape4k webflux Architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-diagram-01.png)

이 예제는 Spring WebFlux 환경에서 IP 주소가 아니라 사용자 토큰을 기준으로 rate limiting을 적용합니다.

참고: Spring WebFlux 환경에서 `UserRateLimitWebFilter`는 요청 정보(`ServerHttpRequest`)의 헤더에서 `X-BLUETAPE4K-UID` 값을 추출한 뒤,
그 값을 기준으로 Bucket4j rate limiting을 적용합니다.

기존 `bucket4j-spring-boot-starter`를 사용자 기반 key와 함께 사용하려면 Spring SpEL이 동기 방식으로 실행되어야 합니다.
해당 방식에서는 헤더에서 사용자 토큰 값을 추출하는 경로가 동기 처리만 지원하므로 성능이 더 느려질 수 있습니다.
