package io.bluetape4k.workshop.exposed.domain.mapping.customId

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnTransformer
import org.jetbrains.exposed.v1.core.ColumnWithTransform
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import java.io.Serializable

@JvmInline
value class Email(val value: String): Serializable {
    companion object {
        val EMPTY = Email("")
    }
}

fun Table.email(name: String, length: Int = 64): Column<Email> =
    registerColumn(name, EmailColumnType(length))

open class EmailColumnType(val length: Int = 64):
    ColumnWithTransform<String, Email>(
        VarCharColumnType(length),
        StringToEmailTransformer()
    )

class StringToEmailTransformer: ColumnTransformer<String, Email> {
    override fun unwrap(email: Email): String = email.value
    override fun wrap(value: String): Email = Email(value)
}
