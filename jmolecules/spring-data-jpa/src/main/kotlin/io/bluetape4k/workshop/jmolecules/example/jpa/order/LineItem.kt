package io.bluetape4k.workshop.jmolecules.example.jpa.order

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import org.jmolecules.ddd.types.Identifier
import java.io.Serializable

data class LineItem(
    val id: LineItem.LineItemId = LineItem.LineItemId(),
): Serializable {

    @JvmInline
    value class LineItemId(
        val id: String = TimebasedUuid.Reordered.nextIdAsString(),
    ): Identifier, Serializable
}
