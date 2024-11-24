package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.orc

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.Army

class OrcArmy: Army {
    companion object {
        internal const val DESCRIPTION = "This is the Orc Army!"
    }

    override val description: String
        get() = DESCRIPTION
}
