package io.bluetape4k.workshop.optimization.clinicappointment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ClinicAppointmentSolverApplication::class])
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class ClinicAppointmentRuntimeContractTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `demo context starts without an external solver service`() {
        ClinicAppointmentSolverApplication::class.java.packageName
            .shouldBeEqualTo("io.bluetape4k.workshop.optimization.clinicappointment")
    }

    @Test
    fun `spring wiring serves the embedded solver proposal`() {
        val response = mockMvc.perform(get("/api/clinic-appointments/demo"))
            .andExpect(status().isOk)
            .andReturn()
            .response

        response.contentAsString.contains("hardScore").shouldBeTrue()
        response.contentAsString.contains("appointment-3").shouldBeTrue()
    }
}
