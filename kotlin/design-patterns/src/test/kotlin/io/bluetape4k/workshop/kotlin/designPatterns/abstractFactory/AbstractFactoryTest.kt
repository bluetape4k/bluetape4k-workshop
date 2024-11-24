package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.elf.ElfArmy
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.elf.ElfCastle
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.elf.ElfKing
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.orc.OrcArmy
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.orc.OrcCastle
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.orc.OrcKing
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class AbstractFactoryTest {

    private val elfFactory = FactoryMaker.makeFactory(FactoryMaker.KingdomType.ELF)
    private val orcFactory = FactoryMaker.makeFactory(FactoryMaker.KingdomType.ORC)

    @Test
    fun `create King`() {
        val elfKing = elfFactory.createKing()
        elfKing shouldBeInstanceOf ElfKing::class

        val orcKing = orcFactory.createKing()
        orcKing shouldBeInstanceOf OrcKing::class
    }

    @Test
    fun `create Castle`() {
        val elfCastle = elfFactory.createCastle()
        elfCastle shouldBeInstanceOf ElfCastle::class

        val orcCastle = orcFactory.createCastle()
        orcCastle shouldBeInstanceOf OrcCastle::class
    }

    @Test
    fun `create Army`() {
        val elfArmy = elfFactory.createArmy()
        elfArmy shouldBeInstanceOf ElfArmy::class

        val orcArmy = orcFactory.createArmy()
        orcArmy shouldBeInstanceOf OrcArmy::class
    }

    @Test
    fun `create ElfKingdom`() {
        with(elfFactory) {
            val king = createKing()
            val castle = createCastle()
            val army = createArmy()

            king shouldBeInstanceOf ElfKing::class
            king.description shouldBeEqualTo ElfKing.DESCRIPTION

            castle shouldBeInstanceOf ElfCastle::class
            castle.description shouldBeEqualTo ElfCastle.DESCRIPTION

            army shouldBeInstanceOf ElfArmy::class
            army.description shouldBeEqualTo ElfArmy.DESCRIPTION
        }
    }

    @Test
    fun `create OrcKingdom`() {
        with(orcFactory) {
            val king = createKing()
            val castle = createCastle()
            val army = createArmy()

            king shouldBeInstanceOf OrcKing::class
            king.description shouldBeEqualTo OrcKing.DESCRIPTION

            castle shouldBeInstanceOf OrcCastle::class
            castle.description shouldBeEqualTo OrcCastle.DESCRIPTION

            army shouldBeInstanceOf OrcArmy::class
            army.description shouldBeEqualTo OrcArmy.DESCRIPTION
        }
    }
}
