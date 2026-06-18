# Builder

[English](README.md) | 한국어

Builder는 필수 식별 정보와 optional construction step을 분리합니다. 이 package에서
`Hero.Builder`는 `profession`, `name`을 필수로 받고, fluent method로 hair, armor, weapon을
추가한 뒤 `build()`에서 immutable `Hero`를 만듭니다.

## 아키텍처

![Builder pattern](../../../../../../../../../../../docs/images/readme-diagrams/kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-builder-readme-architecture-01.png)

## Source map

| source | 역할 |
|---|---|
| `Hero` | private constructor를 가진 target object |
| `Hero.Builder` | optional field를 채우는 fluent builder |
| `Enums.kt` | `Profession`, `HairType`, `HairColor`, `Armor`, `Weapon` option set |
| `HeroDataClass` | default nullable property를 쓰는 Kotlin 대안 |

## 사용 예

```kotlin
val mage = Hero.Builder(Profession.MAGE, "Riobard")
    .withHairColor(HairColor.BLACK)
    .withWeapon(Weapon.DAGGER)
    .build()
```

단순 data holder라면 `HeroDataClass`가 builder보다 명확할 수 있습니다. 생성 단계가 이름을 가져야
하거나 validation point, 복잡한 invariant가 있으면 builder를 쓰는 편이 낫습니다.
