package io.bluetape4k.workshop.spring.modulith.services.organization.model

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.hibernate.model.LongJpaEntity
import jakarta.persistence.Entity

@Entity
class Organization: LongJpaEntity() {

    var name: String? = null
    var address: String? = null

    override fun equalProperties(other: Any): Boolean {
        return other is Organization &&
                name == other.name &&
                address == other.address
    }

    override fun buildStringHelper(): ToStringBuilder {
        return super.buildStringHelper()
            .add("name", name)
            .add("address", address)
    }
}
