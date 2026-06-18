# Abstract Factory

[한국어](README.ko.md) | English

Abstract Factory groups related object creation behind one factory interface. This package models a
kingdom family: every selected factory creates a matching `Castle`, `King`, and `Army`.

## Architecture

![Abstract Factory pattern](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-abstractfactory-readme-architecture-01.png)

## Source map

| source | role |
|---|---|
| `KingdomFactory` | common creation contract: `createCastle()`, `createKing()`, `createArmy()` |
| `FactoryMaker` | chooses `ElfKingdomFactory` or `OrcKingdomFactory` from `KingdomType` |
| `elf/*` | concrete Elf product family |
| `orc/*` | concrete Orc product family |
| `Castle`, `King`, `Army` | product interfaces shared by every family |

## Usage

```kotlin
val factory = FactoryMaker.makeFactory(FactoryMaker.KingdomType.ELF)

val castle = factory.createCastle()
val king = factory.createKing()
val army = factory.createArmy()
```

Use this pattern when the caller should choose a family once and then receive related products that
belong together.
