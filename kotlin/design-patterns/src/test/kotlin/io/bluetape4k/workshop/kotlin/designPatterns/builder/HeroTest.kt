package io.bluetape4k.workshop.kotlin.designPatterns.builder

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class HeroTest {

    companion object: KLogging()

    @Test
    fun `Builder 를 사용하여 Hero 생성하기`() {
        val heroName = "Sir Lancelot"

        val hero = Hero.Builder(Profession.WARRIOR, heroName)
            .withArmor(Armor.CHAIN_MAIL)
            .withWeapon(Weapon.SWORD)
            .withHairType(HairType.LONG_CURLY)
            .withHairColor(HairColor.BLOND)
            .build()

        log.info { "Hero: $hero" }

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
