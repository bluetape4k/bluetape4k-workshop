package io.bluetape4k.workshop.spring.modulith.services.department

interface DepartmentExternalAPI {

    fun getDepartmentByIdWithEmployees(departmentId: Long): DepartmentDTO

    fun add(department: DepartmentDTO): DepartmentDTO
}
