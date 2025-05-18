package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.elf

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.Army
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.Castle
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.King
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.KingdomFactory

class ElfKingdomFactory: KingdomFactory {

    override fun createCastle(): Castle = ElfCastle()

    override fun createKing(): King = ElfKing()

    override fun createArmy(): Army = ElfArmy()
}
