# Spring WebFlux에서 Bucket4j로 사용자별 Rate Limit 적용

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Webflux에서 Bucket4j로 사용자별 Rate Limit 적용**을 실행 가능한 rate limiting 워크숍 조각으로 다룹니다. 개발자가 가장 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API를 중심으로 설명합니다.

## 아키텍처 다이어그램

![Spring WebFlux에서 Bucket4j로 사용자별 Rate Limit 적용 Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.ratelimit` 패키지를 기준으로 삼습니다.
