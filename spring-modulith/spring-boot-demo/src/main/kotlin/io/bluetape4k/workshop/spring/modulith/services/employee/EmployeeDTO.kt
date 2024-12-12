package io.bluetape4k.workshop.spring.modulith.services.employee

import java.io.Serializable

data class EmployeeDTO(
    val id: Long,
    val organizationId: Long,
    val departmentId: Long,
    val name: String,
    val age: Int,
    val position: String,
): Serializable
