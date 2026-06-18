# Singleton

[한국어](README.ko.md) | English

Singleton ensures a type exposes one shared instance through a known access path. This package
compares Kotlin-native implementations with Java-style and lock-guarded variants.

## Architecture

![Singleton pattern](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-singleton-readme-architecture-01.png)

## Source map

| source | role |
|---|---|
| `IvoryTowerObject` | Kotlin `object` singleton |
| `KotlinSingleton` | companion `INSTANCE` initialized with `lazy` |
| `EnumIvoryTower` | single enum instance |
| `IvoryTower` | eager private-constructor singleton with `getInstance()` |
| `InitializingOnDemandHolderIdiom` | nested holder loaded on demand |
| `ThreadSafeLazyLoadedIvoryTower` | lazy instance guarded by `ReentrantLock` |
| `ThreadSafeDoubleCheckLocking` | volatile field plus double-check locking with `ReentrantLock` |

## Guidance

For idiomatic Kotlin code, prefer `object` or a simple `lazy` holder when it satisfies the lifecycle
requirement. The lock-based examples are useful for understanding legacy singleton mechanics and for
cases where construction must be explicitly guarded without `synchronized`.
