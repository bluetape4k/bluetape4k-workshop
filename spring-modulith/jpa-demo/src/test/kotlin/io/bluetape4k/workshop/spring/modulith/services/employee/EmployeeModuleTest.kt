package io.bluetape4k.workshop.spring.modulith.services.employee

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.spring.modulith.services.OrganizationRemoveEvent
import io.bluetape4k.workshop.spring.modulith.services.employee.repository.EmployeeRepository
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario

@ApplicationModuleTest(ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EmployeeModuleTest(
    @param:Autowired private val repository: EmployeeRepository,
) {

    companion object: KLoggingChannel()

    @Test
    @Order(1)
    fun `should remove employees on event`(scenario: Scenario) {
        scenario
            .publish(OrganizationRemoveEvent(1L))
            .andWaitForStateChange {
                repository.count()
            }
            .andVerify { result ->
                result.toInt() shouldBeEqualTo 0
            }
    }
}
