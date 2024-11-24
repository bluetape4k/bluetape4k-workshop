package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.elf

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.Army

class ElfArmy: Army {
    companion object {
        internal const val DESCRIPTION = "This is the Elven Army!"
    }

    override val description: String
        get() = DESCRIPTION
}
