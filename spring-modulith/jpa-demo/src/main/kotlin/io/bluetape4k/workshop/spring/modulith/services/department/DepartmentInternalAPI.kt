package io.bluetape4k.workshop.spring.modulith.services.department

interface DepartmentInternalAPI {

    fun getDepartmentsByOrganizationId(organizationId: Long): List<DepartmentDTO>

    fun getDepartmentsByOrganizationIdWithEmployees(organizationId: Long): List<DepartmentDTO>
}
