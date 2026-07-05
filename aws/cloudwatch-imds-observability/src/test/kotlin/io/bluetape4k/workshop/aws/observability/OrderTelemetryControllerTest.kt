package io.bluetape4k.workshop.aws.observability

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchLogsOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterPublishingOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchOperations
import io.bluetape4k.aws.spring.imds.ImdsOperations
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ObservabilityApplication::class])
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderTelemetryControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val cloudWatchOperations: CloudWatchOperations,
    private val cloudWatchLogsOperations: CloudWatchLogsOperations,
    private val meterPublishingOperations: CloudWatchMeterPublishingOperations,
    private val imdsOperations: ImdsOperations,
) {
    @Test
    fun `local profile wires credential-free AWS observability operations`() {
        cloudWatchOperations.shouldNotBeNull()
        cloudWatchLogsOperations.shouldNotBeNull()
        meterPublishingOperations.shouldNotBeNull()
        imdsOperations.shouldNotBeNull()
    }

    @Test
    fun `POST order telemetry returns local publish report with metadata skipped`() {
        val asyncResult = mockMvc.perform(
            post("/api/aws-observability/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"eventId":"order-web-1","outcome":"SUCCESS","message":"accepted"}"""),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metric.state").value("PUBLISHED"))
            .andExpect(jsonPath("$.logs.state").value("PUBLISHED"))
            .andExpect(jsonPath("$.meterSnapshot.state").value("PUBLISHED"))
            .andExpect(jsonPath("$.metadata.state").value("SKIPPED"))
    }

    @Test
    fun `POST order telemetry reads local metadata only when explicitly requested`() {
        val asyncResult = mockMvc.perform(
            post("/api/aws-observability/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"eventId":"order-web-2","outcome":"SUCCESS","includeMetadata":true}"""),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.state").value("PUBLISHED"))
            .andExpect(jsonPath("$.metadata.instanceId").value("local-instance"))
            .andExpect(jsonPath("$.metadata.region").value("local-region"))
            .andExpect(jsonPath("$.metadata.availabilityZone").value("local-zone"))
    }

    @Test
    fun `GET metadata is explicit local metadata lookup`() {
        val asyncResult = mockMvc.perform(get("/api/aws-observability/metadata"))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("PUBLISHED"))
            .andExpect(jsonPath("$.instanceId").value("local-instance"))
    }

    @Test
    fun `POST order telemetry returns bad request for blank event id`() {
        val asyncResult = mockMvc.perform(
            post("/api/aws-observability/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"eventId":" ","outcome":"SUCCESS"}"""),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("eventId must not be blank."))
    }
}
