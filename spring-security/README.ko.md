# Spring Security Workshop

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Security Workshop**을 실행 가능한 Spring Security request protection 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 아키텍처 다이어그램

![Spring Security Workshop Graphviz 아키텍처 다이어그램](../docs/images/readme-diagrams/spring-security-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springsecurity` 패키지를 기준으로 삼습니다.

## 시퀀스 다이어그램

Spring Security를 사용하는 MVC와 WebFlux security 예제 모음입니다.

## 하위 모듈 구성

## Security Filter Chain Flow

![Security Filter Chain diagram](../docs/images/readme-diagrams/spring-security-diagram-02.png)

## 참고 자료

### 문서

* [Spring Security Reference](https://docs.spring.io/spring-security/reference/)

### 예제

* [spring-security-samples](https://github.com/spring-projects/spring-security-samples)
* [Spring Security OAuth Resource Server demo](https://github.com/arthuroz/spring-security-multi-tenancy)
* [Java Spring Security Example](https://github.com/Yoh0xFF/java-spring-security-example)
