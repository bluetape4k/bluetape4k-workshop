package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.orc

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.Army
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.Castle
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.King
import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.KingdomFactory

class OrcKingdomFactory: KingdomFactory {

    override fun createCastle(): Castle = OrcCastle()

    override fun createKing(): King = OrcKing()

    override fun createArmy(): Army = OrcArmy()
}
