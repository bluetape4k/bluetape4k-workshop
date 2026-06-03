# Vert.x Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Vert.x Demo**를 실행 가능한 Vert.x reactive service 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 아키텍처 다이어그램

![Vert.x Demo Graphviz 아키텍처 다이어그램](../docs/images/readme-diagrams/vertx-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.vertx` 패키지를 기준으로 삼습니다.

## 시퀀스 다이어그램

![Vert.x Demo sequence diagram](../docs/images/readme-diagrams/vertx-coroutines-sequence-01.png)

## 모듈 구조

![vertx Architecture diagram](../docs/images/readme-diagrams/vertx-diagram-01.png)

## 참고 자료

* [Vertx Documents](https://vertx.io/docs/)
* [Vertx Lang Kotlin Coroutines](https://vertx.io/docs/vertx-lang-kotlin-coroutines/kotlin/)
