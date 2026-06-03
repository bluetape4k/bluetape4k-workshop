# lazyLoading

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **lazyLoading**를 실행 가능한 Kotlin 언어와 코루틴 패턴 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.
## 아키텍처 다이어그램

![lazyLoading Graphviz 아키텍처 다이어그램](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-lazyloading-readme-architecture-01.png)

모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.kotlin` 패키지 아래의 구현을 기준으로 삼습니다.

## 시퀀스 다이어그램

---
layout: pattern
title: Lazy Loading
folder: lazy-loading
permalink: /patterns/lazy-loading/
categories: Other
tags:
    - Java
    - Difficulty-Beginner
    - Idiom
    - Performance
---

## 목적

Lazy Loading은 객체가 실제로 필요할 때까지 초기화를 미루기 위해 자주 사용하는 디자인 패턴입니다.
올바르게 사용하면 프로그램의 운영 효율성을 높이는 데 도움이 됩니다.

![Lazy Loading](./doc/lazy-loading.png "Lazy Loading")

## 적용 방법

Lazy Loading 방식은 다음과 같은 경우에 사용합니다.

* 즉시 로딩 비용이 크거나, 로딩할 객체가 전혀 필요하지 않을 수도 있는 경우

## 실제 사례

* JPA annotations @OneToOne, @OneToMany, @ManyToOne, @ManyToMany and fetch = FetchType.LAZY

## 참고

* [J2EE Design Patterns](http://www.amazon.com/J2EE-Design-Patterns-William-Crawford/dp/0596004273/ref=sr_1_2)
