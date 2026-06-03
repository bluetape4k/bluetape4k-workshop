# builder

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **builder** as a runnable Kotlin language and coroutine patterns workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![builder Graphviz architecture diagram](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-builder-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.kotlin` as the source of truth when comparing this README with the code.

## Flow Diagram

1. Prepare the local runtime required by `builder`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

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

## Purpose

Separates the creation of complex objects from their representations, allowing the same creation process to create different representations.

## explanation

real example

> Imagine a character creator for a role-playing game. The easiest option is to let the computer create your character.
> However, choosing character details such as occupation, gender, hair color, etc. makes character creation a step-by-step process that is completed when all choices are in place.

To put it simply

> You can create different flavors of objects and avoid constructor pollution.
> This is useful when there may be multiple flavors of an object. Alternatively, it is useful when object creation involves many steps.

According to Wikipedia

> The Builder pattern is an object creation software design pattern intended to find a solution to the telescope constructor anti-pattern.

So, let me explain a little more about the telescope constructor anti-pattern. At some point we have seen constructors like this:

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

The number of constructor parameters can quickly become overwhelming, and their arrangement can become difficult to understand.
Additionally, this list of parameters can continue to grow if you want to add more options later.
This is called the telescope generator anti-pattern.

**Program example**

An ideal alternative would be to use the Builder pattern. First, there is the Heror we want to create.

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

Then create a builder.

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

Then you can use it like this:

```kotlin
val mage = Hero.Builder(Profession.MAGE, "Riobard")
    .withHairColor(HairColor.BLACK)
    .withWeapon(Weapon.DAGGER)
    .build()
```

## Applicable

When using the Builder pattern

* The creation process must be independent of the parts of the complex object and how the object is constructed
* Should allow different expressions for object creation

## Example

* [java.lang.StringBuilder](http://docs.oracle.com/javase/8/docs/api/java/lang/StringBuilder.html)
* [java.nio.ByteBuffer](http://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html#put-byte-) as well as similar
  buffers such as FloatBuffer, IntBuffer and so on.
* [java.lang.StringBuffer](http://docs.oracle.com/javase/8/docs/api/java/lang/StringBuffer.html#append-boolean-)
* All implementations of [java.lang.Appendable](http://docs.oracle.com/javase/8/docs/api/java/lang/Appendable.html)
* [Apache Camel builders](https://github.com/apache/camel/tree/0e195428ee04531be27a0b659005e3aa8d159d23/camel-core/src/main/java/org/apache/camel/builder)

## reference

* [Design Patterns: Elements of Reusable Object-Oriented Software](http://www.amazon.com/Design-Patterns-Elements-Reusable-Object-Oriented/dp/0201633612)
* [Effective Java (2nd Edition)](http://www.amazon.com/Effective-Java-Edition-Joshua-Bloch/dp/0321356683)
