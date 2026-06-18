# Lazy Loading

[English](README.md) | 한국어

Lazy Loading은 값이 실제로 필요해질 때까지 비용이 큰 초기화를 미룹니다. 이 package는 생성 비용이
큰 객체로 `Heavy`를 사용하고, native, lock-guarded, Kotlin `lazy`, coroutine `DeferredValue`
holder를 비교합니다.

## 아키텍처

![Lazy Loading pattern](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-lazyloading-readme-architecture-01.png)

## Source map

| source | 역할 |
|---|---|
| `Heavy` | 생성 중 sleep을 두어 비용이 큰 객체 생성을 눈에 보이게 함 |
| `HolderNative` | 첫 `getHeavy()` 호출 때 `lateinit` property 초기화 |
| `HolderThreadSafe` | 첫 초기화를 `ReentrantLock`으로 보호 |
| `HolderKotlinLazy` | Kotlin `lazy(LazyThreadSafetyMode.SYNCHRONIZED)` 사용 |
| `coroutines/HeavyDeferredValue` | `DeferredValue`로 suspend initialization 지연 |

## 사용 형태

```kotlin
val holder = HolderKotlinLazy()

// holder 생성만으로 Heavy가 만들어지지 않습니다.
val heavy = holder.getHeavy()
```

eager creation 비용이 크고, 호출자가 값을 아예 쓰지 않을 수도 있다면 lazy loading이 맞습니다.
