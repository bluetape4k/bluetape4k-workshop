package io.bluetape4k.workshop.spring.modulith.services.department.mapper

import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO
import io.bluetape4k.workshop.spring.modulith.services.department.model.Department
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface DepartmentMapper {

    fun toDTO(department: Department): DepartmentDTO

    fun toEntity(departmentDTO: DepartmentDTO): Department
}
