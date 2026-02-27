package io.bluetape4k.workshop.spring.modulith.services

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

@Disabled("왜 endpoint 를 찾지 못하는지 모르겠다")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AppRestControllerTest {
    
    companion object: KLoggingChannel() {
        val faker = Fakers.faker
    }

    @LocalServerPort
    private var port: Int = 0

    private val webClient: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @Test
    @Order(1)
    fun `should add new employee`() = runSuspendIO {
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
            .returnResult<EmployeeDTO>().responseBody
            .awaitSingle()

        emp.id.shouldNotBeNull()
    }

    @Test
    @Order(2)
    fun `should add new department`() = runSuspendIO {
        val departmentDTO = DepartmentDTO(
            organizationId = 1L,
            name = "Test Department"
        )
        val dept = webClient.post()
            .uri("/api/departments")
            .bodyValue(departmentDTO)
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<DepartmentDTO>().responseBody
            .awaitSingle()

        dept.id.shouldNotBeNull()
    }

    @Test
    @Order(3)
    fun `should find department with employees`() = runSuspendIO {
        val dept = webClient.get()
            .uri("/api/departments/{id}/with-employees", mapOf("id" to 1L))
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<DepartmentDTO>().responseBody
            .awaitSingle()

        dept.id.shouldNotBeNull()
    }
}
