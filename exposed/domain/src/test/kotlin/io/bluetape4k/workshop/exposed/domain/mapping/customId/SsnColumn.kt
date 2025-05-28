package io.bluetape4k.workshop.exposed.domain.mapping.customId

import org.jetbrains.exposed.v1.core.CharColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnTransformer
import org.jetbrains.exposed.v1.core.ColumnWithTransform
import org.jetbrains.exposed.v1.core.Table
import java.io.Serializable


@JvmInline
value class Ssn(val value: String): Serializable {
    companion object {
        val EMPTY = Ssn("")
        const val SSN_LENGTH = 14
    }
}

fun Table.ssn(name: String, length: Int = 14): Column<Ssn> =
    registerColumn(name, SsnColumnType(length))

open class SsnColumnType(
    val length: Int = Ssn.SSN_LENGTH,
): ColumnWithTransform<String, Ssn>(CharColumnType(length), StringToSsnTransformer())

class StringToSsnTransformer: ColumnTransformer<String, Ssn> {
    override fun unwrap(ssn: Ssn): String = ssn.value
    override fun wrap(value: String): Ssn = Ssn(value)
}
