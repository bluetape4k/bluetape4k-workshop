package io.bluetape4k.workshop.spring.modulith.services.employee

interface EmployeeExternalAPI {

    fun add(employee: EmployeeDTO): EmployeeDTO

}
