# abstractFactory

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **abstractFactory**를 실행 가능한 Kotlin 언어 및 코루틴 패턴 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리나 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![abstractFactory Graphviz 아키텍처 다이어그램](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-abstractfactory-readme-architecture-01.png)

모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.kotlin` 패키지 아래의 구현을 기준으로 삼습니다.

## 흐름 다이어그램

1. `abstractFactory`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

---
layout: pattern
title: Abstract Factory
folder: abstract-factory
permalink: /patterns/abstract-factory/
categories: Creational
tags:
    - Java
    - Gang Of Four
    - Difficulty-Intermediate
---

## 다른 이름

Kit

## 의도

관련되었거나 서로 의존하는 객체군을 생성하기 위한 인터페이스를 제공합니다.

## 설명

실용적인 예

> 왕국을 만들려면 공통된 주제를 가진 객체가 필요합니다. 엘프 왕국에는 엘프 왕, 엘프 성, 엘프 군대가 필요하고, 오크 왕국에는 오크 왕, 오크 성, 오크 군대가 필요합니다. 왕국 안의 객체들 사이에는 의존성이 있습니다.
> 그렇습니다.

간단히 말하면

> 팩토리들의 팩토리입니다. 구체 클래스를 지정하지 않고 개별적이지만 서로 관련되었거나 의존하는 팩토리들을 함께 묶는 팩토리입니다.

Wikipedia에 따르면

> Abstract Factory 패턴은 구체 클래스를 지정하지 않고 공통 주제를 가진 개별 팩토리 그룹을 캡슐화하는 방법을 제공합니다.

**프로그램 예제**

위 왕국 예제의 변형입니다. 먼저 왕국 객체를 위한 인터페이스와 구현이 있습니다.

![Class Diagram](doc/diagram1.png)

```kotlin
interface Castle {
    val description: String
}
interface King {
    val description: String
}
interface Army {
    val description: String
}

// Elven implementations ->
class ElfCastle implements Castle {
    companion object {
    const val DESCRIPTION = "This is the Elven castle!"
}
    override val description: String
    get() = DESCRIPTION
}
public class ElfKing implements King {
    companion object {
    const val DESCRIPTION = "This is the Elven king!"
}
    override val description: String
    get() = DESCRIPTION
}
public class ElfArmy implements Army {
    companion object {
    const val DESCRIPTION = "This is the Elven Army!"
}
    override val description: String
    get() = DESCRIPTION
}

// Orcish implementations similarly...

```

그다음 왕국 팩토리를 위한 추상화와 구현이 있습니다.

```kotlin
interface KingdomFactory {
    createCastle(): Castle
    createKing(): King
    createArmy(): Army
}

class ElfKingdomFactory implements KingdomFactory {
    override fun createCastle(): Castle {
        return ElfCastle()
    }
    override fun createKing(): King {
        return ElfKing()
    }
    override fun createArmy(): Army {
        return ElfArmy()
    }

}

class OrcKingdomFactory implements KingdomFactory {
    override fun createCastle(): Castle {
        return OrcCastle()
    }
    override fun createKing(): King {
        return OrcKing()
    }
    override fun createArmy(): Army {
        return OrcArmy()
    }
}
```

이제 관련 객체군을 만들 수 있는 추상 팩토리가 생겼습니다. 즉 Elf Kingdom Factory는 엘프 성, 왕, 군대 등을 생성합니다.

```kotlin
val factory = ElfKingdomFactory()
val castle = factory.createCastle()
val king = factory.createKing()
val army = factory.createArmy()

castle.description  // Output: This is the Elven castle!
king.description    // Output: This is the Elven king!
army.description // Output: This is the Elven Army!
```

이제 다른 왕국 팩토리를 위한 팩토리를 설계할 수 있습니다. 이 예제에서는 FactoryMaker를 만들었습니다. 이것은 ElfKingdomFactory 또는 OrcKingdomFactory 인스턴스를 반환합니다.
이 객체는 그 역할을 수행합니다.

FactoryMaker를 사용하면 클라이언트는 원하는 구체 팩토리를 만들 수 있고, 그 팩토리는 여러 구체 객체(군대, 왕, 성)를 생성합니다.
이 예제에서는 클라이언트가 요청할 왕국 팩토리 유형을 enum으로 매개변수화했습니다.

![FactoryMaker](doc/diagram2.png)

```kotlin
object FactoryMaker {

    enum class KingdomType {
        ELF, ORC;
    }

    fun makeFactory(KingdomType type): KindomFactory {
        return when (type) {
            KingdomType.ELF -> ElfKingdomFactory()
            KingdomType.ORC -> OrcKingdomFactory()
            else            -> throw IllegalArgumentException("KingdomType not supported.")
        }
    }
}

fun main(vararg args: String) {
    val app = new App ()

    LOGGER.info("Elf Kingdom")
    app.createKingdom(FactoryMaker.makeFactory(KingdomType.ELF))

    LOGGER.info(app.getArmy().description)
    LOGGER.info(app.getCastle().description)
    LOGGER.info(app.getKing().description)

    LOGGER.info("Orc Kingdom")
    app.createKingdom(FactoryMaker.makeFactory(KingdomType.ORC))
    --similar use of the orc factory
}
```

## 적용 가능성

추상 팩토리 패턴은 다음과 같은 경우에 사용합니다.

* 시스템이 제품의 생성, 구성, 표현 방식과 독립적이어야 하는 경우
* 시스템이 여러 제품군 중 하나로 구성되어야 하는 경우
* 관련된 제품 객체군을 함께 사용하도록 설계했고 이 제약을 강제해야 하는 경우
* 제품을 위한 클래스 라이브러리를 제공하면서 구현이 아닌 인터페이스만 노출하려는 경우
* 의존성의 수명이 개념적으로 소비자의 수명보다 짧은 경우
* 특정 의존성을 구성하기 위해 런타임 값이 필요한 경우
* 제품군 중 어떤 제품을 호출할지 런타임에 결정하려는 경우
* 하나 이상의 매개변수를 런타임에 알아야만 의존성을 해결할 수 있는 경우
* 제품 간 일관성이 필요한 경우
* 기존 코드를 변경하지 않고 프로그램에 새 제품이나 제품군을 추가하려는 경우
*

## 대표 사용 사례

* 런타임에 파일 시스템, 데이터베이스, 네트워크 서비스 중 하나를 호출해야 하는 경우
* 단위 테스트 케이스 작성이 훨씬 쉬워집니다.
* 여러 OS를 위한 UI 도구

## 결과

* Java의 의존성 주입은 런타임 오류를 숨기고 컴파일 타임에 잡을 수 있는 오류를 발생시킬 수 있습니다.
* 이 패턴은 사전에 정의된 객체를 만들기에 좋지만, 새 객체를 추가하기는 어려울 수 있습니다.
* 이 패턴으로 많은 새 인터페이스와 클래스가 도입되면 코드가 예상보다 복잡해질 수 있습니다.

## 예제

* [Abstract Factory Pattern Tutorial](https://www.journaldev.com/1418/abstract-factory-design-pattern-in-java)

## 발표 자료

* [Abstract Factory Pattern](etc/presentation.html)

## 실제 예제

* [javax.xml.parsers.DocumentBuilderFactory](http://docs.oracle.com/javase/8/docs/api/javax/xml/parsers/DocumentBuilderFactory.html)
* [javax.xml.transform.TransformerFactory](http://docs.oracle.com/javase/8/docs/api/javax/xml/transform/TransformerFactory.html#newInstance--)
* [javax.xml.xpath.XPathFactory](http://docs.oracle.com/javase/8/docs/api/javax/xml/xpath/XPathFactory.html#newInstance--)

## 참고

* [Design Patterns: Elements of Reusable Object-Oriented Software](http://www.amazon.com/Design-Patterns-Elements-Reusable-Object-Oriented/dp/0201633612)
