# lazyLoading

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **lazyLoading** as a runnable Kotlin language and coroutine patterns workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.kotlin` as the source of truth when comparing this README with the code.

## Flow Diagram

1. Prepare the local runtime required by `lazyLoading`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

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

## 취지

Lazy Loading은 객체의 초기화를 필요한 시점까지 미루는 데 자주 사용되는 디자인 패턴입니다.
적절하게 사용된다면 프로그램의 작동 효율성에 기여할 수 있습니다.

![Lazy Loading](./doc/lazy-loading.png "Lazy Loading")

## 적용 방법

Lazy Loading 방식은 적용할 때 다음과 같은 경우에 사용합니다.

* eager loading이 비용이 많이 들거나 로드할 객체가 전혀 필요하지 않을 수 있을 때

## 실제 사례

* JPA annotations @OneToOne, @OneToMany, @ManyToOne, @ManyToMany and fetch = FetchType.LAZY

## 참고

* [J2EE Design Patterns](http://www.amazon.com/J2EE-Design-Patterns-William-Crawford/dp/0596004273/ref=sr_1_2)
