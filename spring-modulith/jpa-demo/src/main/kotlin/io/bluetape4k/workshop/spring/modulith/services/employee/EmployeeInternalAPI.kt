package io.bluetape4k.workshop.spring.modulith.services.employee

interface EmployeeInternalAPI {

    fun getEmployeesByDepartmentId(departmentId: Long): List<EmployeeDTO>
    fun getEmployeesByOrganizationId(organizationId: Long): List<EmployeeDTO>
}
