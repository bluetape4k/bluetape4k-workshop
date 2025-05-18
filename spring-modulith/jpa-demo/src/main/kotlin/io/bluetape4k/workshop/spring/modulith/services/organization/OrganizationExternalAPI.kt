package io.bluetape4k.workshop.spring.modulith.services.organization

interface OrganizationExternalAPI {

    fun findByIdWithEmployees(organizationId: Long): OrganizationDTO?
    fun findByIdWithDepartments(organizationId: Long): OrganizationDTO?
    fun findByIdWithDepartmentsAndEmployees(organizationId: Long): OrganizationDTO?

    fun add(organizationDTO: OrganizationDTO): OrganizationDTO
    fun remove(organizationId: Long)
}
