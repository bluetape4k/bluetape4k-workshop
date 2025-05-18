package io.bluetape4k.workshop.spring.modulith.services.organization.mapper

import io.bluetape4k.workshop.spring.modulith.services.organization.OrganizationDTO
import io.bluetape4k.workshop.spring.modulith.services.organization.model.Organization
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface OrganizationMapper {

    fun toDTO(organization: Organization): OrganizationDTO
    fun toEntity(organizationDTO: OrganizationDTO): Organization
}
