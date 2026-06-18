# Builder

[한국어](README.ko.md) | English

Builder separates required identity from optional construction steps. In this package, `Hero.Builder`
requires `profession` and `name`, then optional fluent methods add hair, armor, and weapon choices
before `build()` creates the immutable `Hero`.

## Architecture

![Builder pattern](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-builder-readme-architecture-01.png)

## Source map

| source | role |
|---|---|
| `Hero` | target object with a private constructor |
| `Hero.Builder` | fluent builder for optional fields |
| `Enums.kt` | `Profession`, `HairType`, `HairColor`, `Armor`, `Weapon` option sets |
| `HeroDataClass` | Kotlin alternative using default nullable properties |

## Usage

```kotlin
val mage = Hero.Builder(Profession.MAGE, "Riobard")
    .withHairColor(HairColor.BLACK)
    .withWeapon(Weapon.DAGGER)
    .build()
```

For simple data holders, `HeroDataClass` may be clearer than a builder. Use the builder when creation
has named steps, validation points, or more complex invariants.
