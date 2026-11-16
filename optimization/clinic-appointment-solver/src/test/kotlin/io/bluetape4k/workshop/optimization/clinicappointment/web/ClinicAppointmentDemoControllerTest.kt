package io.bluetape4k.workshop.optimization.clinicappointment.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentProposal
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentSnapshot
import io.bluetape4k.workshop.optimization.clinicappointment.fixture.ClinicAppointmentFixtures
import io.bluetape4k.workshop.optimization.clinicappointment.solver.ClinicAppointmentSolverPort
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ClinicAppointmentDemoControllerTest {

    companion object: KLogging()

    private val controller = ClinicAppointmentDemoController(
        object: ClinicAppointmentSolverPort {
            override fun solve(input: ClinicAppointmentSnapshot): ClinicAppointmentProposal =
                ClinicAppointmentProposal(
                    assignments = emptyList(),
                    hardScore = 0,
                    softScore = 0,
                    feasible = true,
                    unassignedReasons = emptyMap(),
                )
        },
    )
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    @Test
    fun `demo endpoint is read only and returns score projection`() {
        val result = mvc.perform(get("/api/clinic-appointments/demo"))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"feasible":true,"hardScore":0,"softScore":0,"assignments":[],"unassignedReasons":{}}"""))
            .andReturn()

        val responseBody = result.response.contentAsString
        log.debug { "response=$responseBody" }
        responseBody shouldNotContain "patient"

    }

    @Test
    fun `mutation method is not exposed`() {
        mvc.perform(post("/api/clinic-appointments/demo"))
            .andExpect(status().isMethodNotAllowed)
    }

    @Test
    fun `fixture remains available for direct contract checks`() {
        ClinicAppointmentFixtures.snapshot().appointments.size shouldBeEqualTo 3
    }
}
