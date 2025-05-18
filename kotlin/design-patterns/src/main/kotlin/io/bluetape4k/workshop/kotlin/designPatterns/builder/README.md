---
layout: pattern
title: Builder
folder: builder
permalink: /patterns/builder/
categories: Creational
tags:
    - Java
    - Gang Of Four
    - Difficulty-Intermediate
---

## 취지

복잡한 객체의 생성을 표현으로부터 분리하여 동일한 생성 프로세스가 다른 표현을 만들 수 있도록 합니다.

## 설명

실제 예제

> 역할을 하는 게임을 위한 캐릭터 생성기를 상상해보십시오. 가장 쉬운 옵션은 컴퓨터가 캐릭터를 생성하도록 하는 것입니다.
> 그러나 직업, 성별, 머리 색깔 등과 같은 캐릭터 세부 정보를 선택하려면 캐릭터 생성이 모든 선택 사항이 준비되었을 때 완료되는 단계별 프로세스가 됩니다.

간단히 말하면

> 객체의 다양한 플레이버를 생성하고 생성자 오염을 피할 수 있습니다.
> 객체의 여러 가지 플레이버가 있을 수 있는 경우 유용합니다. 또는 객체 생성에 많은 단계가 포함되어 있는 경우 유용합니다.

Wikipedia에 따르면

> 빌더 패턴은 망원경 생성자 안티 패턴에 대한 해결책을 찾기 위한 의도로 하는 객체 생성 소프트웨어 디자인 패턴입니다.

그렇다면 망원경 생성자 안티 패턴에 대해 조금 더 설명하겠습니다. 어느 순간이든 우리는 아래와 같은 생성자를 본 적이 있습니다.:

```kotlin
class Hero(
    profession: Profession,
    name: Name,
    hairType: HairType,
    hairColor: HairColor,
    armor: Armor,
    weapon: Weapon,
) {
    // ...
}
```

생성자 매개변수의 수가 금방 너무 많아질 수 있으며 매개변수의 배열을 이해하기 어려워질 수 있습니다.
또한 이 매개변수 목록은 나중에 더 많은 옵션을 추가하려는 경우 계속해서 증가할 수 있습니다.
이것은 망원경 생성자 안티 패턴이라고 합니다.

**프로그램 예제**

이상적인 대안은 빌더 패턴을 사용하는 것입니다. 먼저 만들고자 하는 Heror가 있습니다.

```kotlin
class Hero private constructor(builder: Hero.Builder) {

    val profession = builder.profession
    val name = builder.name
    val hairType = builder.hairType
    val hairColor = builder.hairColor
    val armor = builder.armor
    val weapon = builder.weapon

    override fun toString(): String {
        return buildString {
            append("This is a ")
                .append(profession)
                .append(" named ")
                .append(name)
            if (hairColor != null || hairType != null) {
                append(" with ")
                hairColor?.run { append(this).append(' ') }
                hairType?.run { append(this).append(' ') }
            }
            armor?.run { append(" wearing ").append(this) }
            weapon?.run { append(" and wielding a ").append(this) }
            append('.')
        }
    }
    //...
}
```

그런 다음 빌더를 만듭니다.

```kotlin
class Builder(val profession: Profession, val name: String) {
    var hairType: HairType? = null
    var hairColor: HairColor? = null
    var armor: Armor? = null
    var weapon: Weapon? = null
    fun withHairType(hairType: HairType) = apply {
        this.hairType = hairType
    }
    fun withHairColor(hairColor: HairColor) = apply {
        this.hairColor = hairColor
    }
    fun withArmor(armor: Armor) = apply {
        this.armor = armor
    }
    fun withWeapon(weapon: Weapon) = apply {
        this.weapon = weapon
    }
    fun build(): Hero {
        return Hero(this)
    }
}
```

그런 다음 다음과 같이 사용할 수 있습니다 :

```kotlin
val mage = Hero.Builder(Profession.MAGE, "Riobard")
    .withHairColor(HairColor.BLACK)
    .withWeapon(Weapon.DAGGER)
    .build()
```

## 적용 가능

Builder 패턴 사용하는 경우

* 생성 프로세스가 복잡한 객체의 부분과 객체를 구성하는 방법과 독립적이어야 합니다
* 객체 생성에 대한 다른 표현을 허용해야 합니다

## 예제

* [java.lang.StringBuilder](http://docs.oracle.com/javase/8/docs/api/java/lang/StringBuilder.html)
* [java.nio.ByteBuffer](http://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html#put-byte-) as well as similar
  buffers such as FloatBuffer, IntBuffer and so on.
* [java.lang.StringBuffer](http://docs.oracle.com/javase/8/docs/api/java/lang/StringBuffer.html#append-boolean-)
* All implementations of [java.lang.Appendable](http://docs.oracle.com/javase/8/docs/api/java/lang/Appendable.html)
* [Apache Camel builders](https://github.com/apache/camel/tree/0e195428ee04531be27a0b659005e3aa8d159d23/camel-core/src/main/java/org/apache/camel/builder)

## 참고

* [Design Patterns: Elements of Reusable Object-Oriented Software](http://www.amazon.com/Design-Patterns-Elements-Reusable-Object-Oriented/dp/0201633612)
* [Effective Java (2nd Edition)](http://www.amazon.com/Effective-Java-Edition-Joshua-Bloch/dp/0321356683)
