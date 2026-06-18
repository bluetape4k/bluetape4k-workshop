# Abstract Factory

[English](README.md) | 한국어

Abstract Factory는 관련 객체 생성을 하나의 factory interface 뒤로 묶습니다. 이 package는 왕국
family를 모델링합니다. 선택된 factory는 서로 어울리는 `Castle`, `King`, `Army`를 생성합니다.

## 아키텍처

![Abstract Factory pattern](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-abstractfactory-readme-architecture-01.png)

## Source map

| source | 역할 |
|---|---|
| `KingdomFactory` | 공통 생성 계약: `createCastle()`, `createKing()`, `createArmy()` |
| `FactoryMaker` | `KingdomType`으로 `ElfKingdomFactory` 또는 `OrcKingdomFactory` 선택 |
| `elf/*` | Elf concrete product family |
| `orc/*` | Orc concrete product family |
| `Castle`, `King`, `Army` | 모든 family가 공유하는 product interface |

## 사용 예

```kotlin
val factory = FactoryMaker.makeFactory(FactoryMaker.KingdomType.ELF)

val castle = factory.createCastle()
val king = factory.createKing()
val army = factory.createArmy()
```

호출자가 family를 한 번 선택한 뒤, 서로 맞는 관련 product들을 받아야 할 때 이 pattern이 맞습니다.
