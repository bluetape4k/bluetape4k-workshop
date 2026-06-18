# Kotlin Design Patterns

[한국어](README.ko.md) | English

This module contains small Kotlin implementations of classic design patterns. The examples are
organized as source packages rather than a runnable application: each package keeps the pattern
interfaces, concrete implementations, and a short README focused on how the Kotlin code models the
pattern.

## Pattern catalog

![Kotlin design pattern examples](../../docs/images/readme-diagrams/kotlin-design-patterns-readme-architecture-01.png)

## Implemented patterns

| package | pattern | implementation focus |
|---|---|---|
| `abstractFactory/` | Abstract Factory | `FactoryMaker` selects an `ElfKingdomFactory` or `OrcKingdomFactory`; each factory creates a consistent `Castle`, `King`, and `Army` family |
| `builder/` | Builder | `Hero.Builder` keeps required constructor data separate from optional fluent steps; `HeroDataClass` shows the Kotlin default-argument alternative |
| `lazyLoading/` | Lazy Loading | `HolderNative`, `HolderThreadSafe`, `HolderKotlinLazy`, and coroutine `DeferredValue` defer expensive `Heavy` construction |
| `singleton/` | Singleton | Enum, Kotlin `object`, eager singleton, lazy singleton, holder idiom, and lock-based variants are compared |

## How to read the module

1. Open the package README for the pattern you want.
2. Compare the diagram with the source files in the same package.
3. Use the Kotlin examples as implementation sketches, not framework infrastructure.

The examples intentionally stay small so the pattern mechanics are visible without unrelated
application code.
