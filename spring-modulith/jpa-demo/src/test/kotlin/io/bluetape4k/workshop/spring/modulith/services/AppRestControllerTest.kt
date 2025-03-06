package io.bluetape4k.workshop.spring.modulith.services

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AppRestControllerTest(
    @Autowired private val webClient: WebTestClient,
) {

    companion object: KLogging() {
        val faker = Fakers.faker
    }

    @Test
    @Order(1)
    fun `should add new employee`() {
        val employeeDTO = EmployeeDTO(
            organizationId = 1L,
            departmentId = 1L,
            name = "Test",
            age = 30,
            position = "HR"
        )
        val emp = webClient.post()
            .uri("/api/employees")
            .bodyValue(employeeDTO)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<EmployeeDTO>()
            .returnResult().responseBody!!

        emp.id.shouldNotBeNull()
    }

    @Test
    @Order(2)
    fun `should add new department`() {
        val departmentDTO = DepartmentDTO(
            organizationId = 1L,
            name = "Test Department"
        )
        val dept = webClient.post()
            .uri("/api/departments")
            .bodyValue(departmentDTO)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<DepartmentDTO>()
            .returnResult().responseBody!!

        dept.id.shouldNotBeNull()
    }

    @Test
    @Order(3)
    fun `should find department with employees`() {
        val dept = webClient.get()
            .uri("/api/departments/{id}/with-employees", mapOf("id" to 1L))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<DepartmentDTO>()
            .returnResult().responseBody!!

        dept.id.shouldNotBeNull()
    }
}
