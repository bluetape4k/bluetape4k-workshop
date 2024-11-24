package io.bluetape4k.workshop.kotlin.patterns

import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class FactoryMethod {

    private interface ChessPiece {
        val file: Char
        val rank: Char
    }

    private data class Pawn(
        override val file: Char,
        override val rank: Char,
    ): ChessPiece

    private data class Queen(
        override val file: Char,
        override val rank: Char,
    ): ChessPiece

    private fun createPiece(notation: String): ChessPiece {
        val (type, file, rank) = notation.toCharArray()
        return when (type) {
            'q'  -> Queen(file, rank)
            'p'  -> Pawn(file, rank)
            else -> throw IllegalArgumentException("Unknown piece type: $type")
        }
    }

    @Test
    fun `Factory Method Pattern`() {
        val queen = createPiece("qD4")
        queen shouldBeInstanceOf Queen::class

        val pawn = createPiece("pE2")
        pawn shouldBeInstanceOf Pawn::class
    }
}
