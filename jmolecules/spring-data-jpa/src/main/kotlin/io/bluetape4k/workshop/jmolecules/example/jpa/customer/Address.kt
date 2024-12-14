package io.bluetape4k.workshop.jmolecules.example.jpa.customer

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import org.jmolecules.ddd.types.Entity
import org.jmolecules.ddd.types.Identifier
import java.io.Serializable

data class Address(
    val street: String,
    val city: String,
    val zipCode: String,
    override val id: AddressId = AddressId(TimebasedUuid.Reordered.nextIdAsString()),
): Entity<Customer, Address.AddressId> {

    data class AddressId(val id: String): Identifier, Serializable
}
