package io.bluetape4k.workshop.kotlin.designPatterns.builder

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class HeroDataClassTest {

    companion object: KLogging()

    @Test
    fun `Kotlin Data Class 로 생성하기`() {
        val heroName = "Sir Lancelot"

        val hero = HeroDataClass(
            profession = Profession.WARRIOR,
            name = heroName,
            armor = Armor.CHAIN_MAIL,
            weapon = Weapon.SWORD,
            hairType = HairType.LONG_CURLY,
            hairColor = HairColor.BLOND
        )

        log.info { "HeroDataClass: $hero" }

        hero.shouldNotBeNull()
        hero.toString().shouldNotBeEmpty()

        hero.profession shouldBeEqualTo Profession.WARRIOR
        hero.name shouldBeEqualTo heroName
        hero.armor shouldBeEqualTo Armor.CHAIN_MAIL
        hero.weapon shouldBeEqualTo Weapon.SWORD
        hero.hairType shouldBeEqualTo HairType.LONG_CURLY
        hero.hairColor shouldBeEqualTo HairColor.BLOND
    }
}
