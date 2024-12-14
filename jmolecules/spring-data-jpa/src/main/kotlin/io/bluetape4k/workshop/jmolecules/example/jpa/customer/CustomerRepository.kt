package io.bluetape4k.workshop.jmolecules.example.jpa.customer

import org.jmolecules.ddd.integration.AssociationResolver
import org.jmolecules.ddd.types.Repository

interface CustomerRepository
    : Repository<Customer, Customer.CustomerId>,
      AssociationResolver<Customer, Customer.CustomerId> {

    fun save(customer: Customer): Customer
}
