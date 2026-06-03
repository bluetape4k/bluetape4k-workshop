# singleton

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **singleton**을 실행 가능한 Kotlin 언어 및 코루틴 패턴 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리나 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![singleton Graphviz 아키텍처 다이어그램](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-singleton-readme-architecture-01.png)

모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.kotlin` 패키지 아래의 구현을 기준으로 삼습니다.

## 흐름 다이어그램

1. `singleton`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

---
layout: pattern
title: Singleton
folder: singleton
permalink: /patterns/singleton/
categories: Creational
tags:
    - Java
    - Gang Of Four
    - Difficulty-Beginner
---

## 의도

클래스가 오직 하나의 인스턴스만 갖도록 보장하고, 그 인스턴스에 접근할 전역 접근 지점을 제공합니다.

## 설명

실용적인 예

> 마법사들이 마법을 연구하는 상점은 하나뿐입니다. 마법사들은 항상 같은 상점을 사용합니다. 여기서 상점은 싱글턴입니다.

평범한 말로 표현하면

> 특정 클래스의 객체가 한 번만 생성되도록 보장합니다.

Wikipedia에 따르면

> 소프트웨어 엔지니어링에서 Singleton 패턴은 클래스의 인스턴스화를 하나의 객체로 제한하는 소프트웨어 디자인 패턴입니다. 시스템 전체에서 조정이 필요할 때 유용합니다.

**프로그램 예제**

Joshua Bloch, Effective Java 2nd Edition p.18

> 단일 요소 열거 타입은 싱글턴을 구현하는 가장 좋은 방법입니다.

```kotlin
enum class EnumIvoryTower {
  INSTANCE;
}
```

사용 방법은 다음과 같습니다.

```kotlin
val enumIvoryTower1 = EnumIvoryTower.INSTANCE;
val enumIvoryTower2 = EnumIvoryTower.INSTANCE;
assertTrue { enumIvoryTower1 === enumIvoryTower2 }
```

## 적용 가능성

싱글턴 패턴은 다음과 같은 경우에 사용합니다.

* 클래스 인스턴스가 정확히 하나만 있어야 하고, 이 인스턴스가 잘 알려진 접근 지점을 통해 클라이언트에 접근 가능해야 하는 경우
* 고유 인스턴스를 하위 클래스로 확장할 수 있어야 하고, 클라이언트가 코드를 수정하지 않고 확장 인스턴스를 사용할 수 있어야 하는 경우

## 대표 사용 사례

* Logging class
* Database connection management
* File manager

## 실제 예제

* [java.lang.Runtime#getRuntime()](http://docs.oracle.com/javase/8/docs/api/java/lang/Runtime.html#getRuntime%28%29)
* [java.awt.Desktop#getDesktop()](http://docs.oracle.com/javase/8/docs/api/java/awt/Desktop.html#getDesktop--)
* [java.lang.System#getSecurityManager()](http://docs.oracle.com/javase/8/docs/api/java/lang/System.html#getSecurityManager--)

## 결과

* 싱글턴은 자신만의 생성과 생명주기를 제어하므로 단일 책임 원칙(SRP)을 위반합니다.
* 전역 공유 인스턴스 사용을 권장하게 되어 객체와 객체가 사용하는 리소스가 해제되는 것을 막습니다.
* 강하게 결합된 코드를 만듭니다. 싱글턴의 클라이언트는 테스트하기 어려워집니다.
* 싱글턴을 하위 클래스로 확장하는 것은 거의 불가능합니다.
* 싱글턴은 전역 상태를 만들기 때문에 상태를 변경하거나 숨기기 어렵게 만듭니다.

## 크레딧(참고)

* [Design Patterns: Elements of Reusable Object-Oriented Software](http://www.amazon.com/Design-Patterns-Elements-Reusable-Object-Oriented/dp/0201633612)
* [Effective Java (2nd Edition)](http://www.amazon.com/Effective-Java-Edition-Joshua-Bloch/dp/0321356683)
