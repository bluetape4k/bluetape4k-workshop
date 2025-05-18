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

## Intent (취지)

클래스가 오직 하나의 인스턴스만 가지도록 하고, 그 인스턴스에 대한 전역적인 접근점을 제공합니다.

## Explanation (설명)

실전 예제

> 마법사들이 마법을 연구하는 유일한 상점이 있습니다. 마법사들은 항상 같은 상점을 사용합니다. 여기서 상점은 싱글톤입니다.

평범한 말로는

> 특정 클래스의 객체가 한 번만 생성되도록 보장합니다.

WikiPedia에 따르면

> 소프트웨어 공학에서 싱글톤 패턴은 클래스의 인스턴스화를 하나의 객체로 제한하는 소프트웨어 디자인 패턴입니다. 시스템 전체에서 조정 작업을 수행해야 하는 경우에 유용합니다.

**프로그램 예제**

Joshua Bloch, Effective Java 2nd Edition p.18

> 단일 요소 열거형 유형은 싱글톤을 구현하는 가장 좋은 방법입니다.

```kotlin
enum class EnumIvoryTower {
  INSTANCE;
}
```

그런 다음 사용하려면

```kotlin
val enumIvoryTower1 = EnumIvoryTower.INSTANCE;
val enumIvoryTower2 = EnumIvoryTower.INSTANCE;
assertTrue { enumIvoryTower1 === enumIvoryTower2 }
```

## 적용 가능 여부

싱글톤 패턴을 사용하는 경우

* 클래스의 인스턴스가 정확히 하나이어야 하며, 이 인스턴스는 잘 알려진 접근 지점을 통해 클라이언트에게 접근 가능해야 합니다.
* 유일한 인스턴스는 하위 클래스화가 가능해야 하며, 클라이언트는 코드를 수정하지 않고 확장된 인스턴스를 사용할 수 있어야 합니다.

## 대표적 사용 사례

* 로깅 클래스
* 데이터베이스 연결 관리
* 파일 관리자

## 실제 예제

* [java.lang.Runtime#getRuntime()](http://docs.oracle.com/javase/8/docs/api/java/lang/Runtime.html#getRuntime%28%29)
* [java.awt.Desktop#getDesktop()](http://docs.oracle.com/javase/8/docs/api/java/awt/Desktop.html#getDesktop--)
* [java.lang.System#getSecurityManager()](http://docs.oracle.com/javase/8/docs/api/java/lang/System.html#getSecurityManager--)

## Consequences (결과)

* 싱글톤은 자신의 생성 및 라이프사이클을 제어함으로써 단일 책임 원칙(SRP)을 위반합니다.
* 객체 및 이 객체에서 사용하는 리소스가 해제되지 못하도록 하는 전역 공유 인스턴스를 사용하도록 권장합니다.
* 강하게 결합된 코드를 생성합니다. 싱글톤의 클라이언트는 테스트하기 어려워집니다.
* 싱글톤을 서브클래스화하는 것이 거의 불가능합니다.
* 싱글톤은 전역 상태를 만들어, 상태를 변경하거나 숨기는 것이 어렵게 만듭니다.

## Credits (참고)

* [Design Patterns: Elements of Reusable Object-Oriented Software](http://www.amazon.com/Design-Patterns-Elements-Reusable-Object-Oriented/dp/0201633612)
* [Effective Java (2nd Edition)](http://www.amazon.com/Effective-Java-Edition-Joshua-Bloch/dp/0321356683)
