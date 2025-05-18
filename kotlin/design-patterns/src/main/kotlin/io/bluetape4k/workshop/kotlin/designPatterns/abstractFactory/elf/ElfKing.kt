package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.elf

import io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory.King

class ElfKing: King {

    companion object {
        internal const val DESCRIPTION = "This is the Elven King!"
    }

    override val description: String
        get() = DESCRIPTION
}
