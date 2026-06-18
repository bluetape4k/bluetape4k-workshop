# Kotlin Design Patterns

[English](README.md) | 한국어

이 모듈은 고전적인 design pattern을 작은 Kotlin 구현으로 보여줍니다. 실행 애플리케이션이 아니라
source package별 예제 모음이며, 각 package에는 pattern interface, concrete implementation,
그리고 코드 구조를 설명하는 짧은 README가 있습니다.

## Pattern catalog

![Kotlin design pattern examples](../../docs/images/readme-diagrams/kotlin-design-patterns-readme-architecture-01.png)

## 구현된 패턴

| package | pattern | 구현 초점 |
|---|---|---|
| `abstractFactory/` | Abstract Factory | `FactoryMaker`가 `ElfKingdomFactory` 또는 `OrcKingdomFactory`를 선택하고, 각 factory가 일관된 `Castle`, `King`, `Army` family를 생성 |
| `builder/` | Builder | `Hero.Builder`가 필수 생성 데이터와 optional fluent step을 분리하고, `HeroDataClass`가 Kotlin default argument 대안을 보여줌 |
| `lazyLoading/` | Lazy Loading | `HolderNative`, `HolderThreadSafe`, `HolderKotlinLazy`, coroutine `DeferredValue`가 비용이 큰 `Heavy` 생성을 지연 |
| `singleton/` | Singleton | Enum, Kotlin `object`, eager singleton, lazy singleton, holder idiom, lock 기반 variant 비교 |

## 읽는 순서

1. 보고 싶은 pattern의 package README를 엽니다.
2. diagram과 같은 package의 source file을 비교합니다.
3. 이 예제는 framework infrastructure가 아니라 pattern mechanics를 보여주는 Kotlin sketch로 읽으면 됩니다.

예제는 의도적으로 작게 유지되어 pattern 구조만 눈에 들어오도록 했습니다.
