package io.bluetape4k.workshop.jmolecules.example.jpa.customer

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import org.jmolecules.ddd.types.AggregateRoot
import org.jmolecules.ddd.types.Identifier
import java.io.Serializable

open class Customer: AggregateRoot<Customer, Customer.CustomerId> {

    companion object {
        operator fun invoke(firstname: String, lastname: String, address: Address): Customer {
            return Customer().apply {
                name = Name(firstname, lastname)
                addresses.add(address)
            }
        }
    }

    override var id: Customer.CustomerId = CustomerId(TimebasedUuid.Reordered.nextIdAsString())

    open var name: Name? = null

    val addresses: MutableList<Address> = mutableListOf()

    data class CustomerId(val id: String): Identifier, Serializable

    data class Name(val firstname: String, val lastname: String)
}
