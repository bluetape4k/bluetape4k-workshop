# singleton

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **singleton** as a runnable Kotlin language and coroutine patterns workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![singleton Graphviz architecture diagram](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-singleton-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.kotlin` as the source of truth when comparing this README with the code.

## Intent

Ensures that a class has only one instance and provides a global access point to that instance.

## Explanation

Practical example

> There is only one store where wizards research magic. Wizards always use the same store. Here the store is a singleton.

In plain words

> Ensures that objects of a particular class are created only once.

According to WikiPedia

> In software engineering, the Singleton pattern is a software design pattern that limits the instantiation of a class to a single object. This is useful when you need to make adjustments across your system.

**Program example**

Joshua Bloch, Effective Java 2nd Edition p.18

> Single-element enumerated types are the best way to implement singletons.

```kotlin
enum class EnumIvoryTower {
  INSTANCE;
}
```

Then to use

```kotlin
val enumIvoryTower1 = EnumIvoryTower.INSTANCE;
val enumIvoryTower2 = EnumIvoryTower.INSTANCE;
assertTrue { enumIvoryTower1 === enumIvoryTower2 }
```

## Applicability

When to use the singleton pattern

* There must be exactly one instance of the class, and this instance must be accessible to clients through a well-known access point.
* Unique instances must be able to be subclassed, and clients must be able to use extended instances without modifying their code.

## Representative use cases

* Logging class
* Database connection management
* File manager

## Real example

* [java.lang.Runtime#getRuntime()](http://docs.oracle.com/javase/8/docs/api/java/lang/Runtime.html#getRuntime%28%29)
* [java.awt.Desktop#getDesktop()](http://docs.oracle.com/javase/8/docs/api/java/awt/Desktop.html#getDesktop--)
* [java.lang.System#getSecurityManager()](http://docs.oracle.com/javase/8/docs/api/java/lang/System.html#getSecurityManager--)

## Consequences

* Singletons violate the Single Responsibility Principle (SRP) by controlling their own creation and lifecycle.
* We recommend using global shared instances, which prevents objects and the resources they use from being released.
* Generates tightly coupled code. A singleton's clients become difficult to test.
* It is almost impossible to subclass a singleton.
* Singletons create global state, making it difficult to change or hide the state.

## Credits (reference)

* [Design Patterns: Elements of Reusable Object-Oriented Software](http://www.amazon.com/Design-Patterns-Elements-Reusable-Object-Oriented/dp/0201633612)
* [Effective Java (2nd Edition)](http://www.amazon.com/Effective-Java-Edition-Joshua-Bloch/dp/0321356683)
