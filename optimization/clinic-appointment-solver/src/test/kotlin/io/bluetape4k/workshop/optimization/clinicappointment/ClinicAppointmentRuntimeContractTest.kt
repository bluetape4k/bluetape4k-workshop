package io.bluetape4k.workshop.optimization.clinicappointment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ClinicAppointmentSolverApplication::class])
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class ClinicAppointmentRuntimeContractTest {

    companion object: KLogging()

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

        val content = response.contentAsString
        log.debug { "content=$content" }
        content shouldContain "hardScore"
        content shouldContain "appointment-3"
    }
}
