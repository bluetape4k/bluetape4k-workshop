package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.elf.ElfKingdomFactory
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.orc.OrcKingdomFactory

object FactoryMaker {

    enum class KingdomType {
        ELF, ORC
    }

    fun makeFactory(type: KingdomType): KingdomFactory =
        when (type) {
            KingdomType.ELF -> ElfKingdomFactory()
            KingdomType.ORC -> OrcKingdomFactory()
            // else            -> throw IllegalArgumentException("KingdomType not supported.")
        }
}
