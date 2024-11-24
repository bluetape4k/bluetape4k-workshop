package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.elf

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.Castle

class ElfCastle: Castle {
    companion object {
        internal const val DESCRIPTION = "This is the Elven Castle!"
    }

    override val description: String
        get() = DESCRIPTION
}
