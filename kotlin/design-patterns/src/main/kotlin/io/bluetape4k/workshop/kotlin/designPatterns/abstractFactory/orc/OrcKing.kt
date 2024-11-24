package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.orc

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.King

class OrcKing: King {

    companion object {
        internal const val DESCRIPTION = "This is the Orc King!"
    }

    override val description: String
        get() = DESCRIPTION
}
