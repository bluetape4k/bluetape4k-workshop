package io.bluetape4k.workshop.spring.modulith.services.employee.mapper

import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import io.bluetape4k.workshop.spring.modulith.services.employee.model.Employee
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface EmployeeMapper {

    fun toDTO(employee: Employee): EmployeeDTO
    fun toEntity(employeeDTO: EmployeeDTO): Employee

}
