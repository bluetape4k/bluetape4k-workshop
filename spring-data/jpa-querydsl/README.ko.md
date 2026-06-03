# JPA & QueryDSL Example

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **JPA & QueryDSL Example**을 실행 가능한 Spring Data 영속성 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![JPA & QueryDSL Example Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/spring-data-jpa-querydsl-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README와 코드를 비교할 때는 `io.bluetape4k.workshop.springdata` 패키지를 기준으로 삼으세요.

## 시퀀스 다이어그램

![JPA & QueryDSL Example sequence diagram](../../docs/images/readme-diagrams/spring-data-jpa-querydsl-sequence-01.png)

Spring Boot를 사용하는 JPA & QueryDSL 예제입니다.
