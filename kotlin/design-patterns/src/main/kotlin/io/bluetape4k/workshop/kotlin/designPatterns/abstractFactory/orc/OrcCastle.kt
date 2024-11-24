package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.orc

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.Castle

class OrcCastle: Castle {
    companion object {
        internal const val DESCRIPTION = "This is the Orc Castle!"
    }

    override val description: String
        get() = DESCRIPTION
}
