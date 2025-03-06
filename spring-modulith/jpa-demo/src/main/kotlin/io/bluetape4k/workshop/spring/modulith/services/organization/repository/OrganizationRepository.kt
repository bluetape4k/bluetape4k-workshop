package io.bluetape4k.workshop.spring.modulith.services.organization.repository

import io.bluetape4k.workshop.spring.modulith.services.organization.OrganizationDTO
import io.bluetape4k.workshop.spring.modulith.services.organization.model.Organization
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface OrganizationRepository: JpaRepository<Organization, Long> {

    @Query(
        """
        SELECT new io.bluetape4k.workshop.spring.modulith.services.organization.OrganizationDTO(            
            o.id,
            o.name,
            o.address
        )
        FROM Organization o
        WHERE o.id = :id
        """
    )
    fun findDTOById(id: Long): OrganizationDTO?
}
