package io.bluetape4k.workshop.spring.modulith.services.department

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.services.OrganizationAddEvent
import io.bluetape4k.workshop.spring.modulith.services.OrganizationRemoveEvent
import io.bluetape4k.workshop.spring.modulith.services.department.mapper.DepartmentMapper
import io.bluetape4k.workshop.spring.modulith.services.department.repository.DepartmentRepository
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario

@ApplicationModuleTest(ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DepartmentModuleTest(
    @Autowired private val repository: DepartmentRepository,
    @Autowired private val mapper: DepartmentMapper,
) {

    companion object: KLogging() {
        const val TEST_ID = 100L
    }

    @Test
    @Order(1)
    fun `should add department on event`(scenario: Scenario) {
        scenario.publish(OrganizationAddEvent(TEST_ID))
            .andWaitForStateChange {
                repository.findDTOByOrganizationId(TEST_ID)
            }
            .andVerify { result ->
                result.shouldNotBeEmpty()
            }
    }

    @Test
    @Order(2)
    fun `should remove departments on event`(scenario: Scenario) {
        scenario.publish(OrganizationRemoveEvent(TEST_ID))
            .andWaitForStateChange {
                // 상태 변화가 완료되었다고 했는데, 아직 삭제되지 않은 경우가 있다.
                Thread.sleep(10)
                repository.findDTOByOrganizationId(TEST_ID)
            }
            .andVerify { result ->
                result.shouldBeEmpty()
            }
    }
}
