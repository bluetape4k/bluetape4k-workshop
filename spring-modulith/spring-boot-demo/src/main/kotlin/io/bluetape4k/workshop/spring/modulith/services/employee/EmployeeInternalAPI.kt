package io.bluetape4k.workshop.spring.modulith.services.employee

interface EmployeeInternalAPI {

    fun getEmployeesByDepartmentId(departmentId: Long): MutableList<EmployeeDTO>
    fun getEmployeesByOrganizationId(organizationId: Long): MutableList<EmployeeDTO>
}
