package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@TestPropertySource(properties = ["workshop.voucher.sse.heartbeat-interval=100ms"])
internal class VoucherEventStreamIntegrationTest : AbstractVoucherIntegrationTest() {
    @Autowired
    private lateinit var streams: VoucherEventStream

    @Test
    fun `stream starts with an authoritative campaign snapshot`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        val request =
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/campaigns/$campaignId/events"))
                .timeout(STREAM_TIMEOUT)
                .header("Accept", "text/event-stream")
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, randomIdentifier())
                .GET()
                .build()

        val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.statusCode() shouldBeEqualTo 200
        val firstEvent =
            response.body().bufferedReader().use { reader ->
                generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
            }

        firstEvent.any { it == "event: snapshot" }.shouldBeTrue()
        firstEvent.any { it.startsWith("id: ") && it.substringAfter("id: ").contains(':') }.shouldBeTrue()
        firstEvent.any { it.startsWith("data: ") && it.contains("\"campaignId\":\"$campaignId\"") }.shouldBeTrue()
    }

    @Test
    fun `closing the last subscription uses one cleanup path`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)
        val activeBefore = streams.activePollers()

        val subscription = streams.open(tenant, campaignId, null)
        streams.activePollers() shouldBeEqualTo activeBefore + 1

        subscription.close()
        subscription.close()

        subscription.cleanupInvocationCount() shouldBeEqualTo 1L
        subscription.queueDepth() shouldBeEqualTo 0
        streams.activePollers() shouldBeEqualTo activeBefore
    }

    @Test
    fun `retention gap emits snapshot then authoritative reset`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)
        val subscription = streams.open(tenant, campaignId, EventCursor(0, 0))

        val snapshot = subscription.next(Duration.ZERO).shouldNotBeNull()
        val reset = subscription.next(Duration.ZERO).shouldNotBeNull()
        subscription.close()

        snapshot.event shouldBeEqualTo "snapshot"
        reset.event shouldBeEqualTo "reset"
        reset.cursor shouldBeEqualTo snapshot.cursor
    }

    @Test
    fun `cross tenant cursor is rejected`() {
        val firstTenant = randomIdentifier()
        val firstCampaign = createActiveCampaign(firstTenant)
        val first = streams.open(firstTenant, firstCampaign, null)
        val foreignCursor = first.next(Duration.ZERO).shouldNotBeNull().cursor
        first.close()

        val secondTenant = randomIdentifier()
        val secondCampaign = createActiveCampaign(secondTenant)
        val failure = runCatching { streams.open(secondTenant, secondCampaign, foreignCursor) }.exceptionOrNull()

        failure.shouldBeInstanceOf<VoucherApiException>().stableCode shouldBeEqualTo "INVALID_EVENT_CURSOR"
    }

    @Test
    fun `resume cursor starts from a fresh snapshot without reset`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)
        val first = streams.open(tenant, campaignId, null)
        val cursor = first.next(Duration.ZERO).shouldNotBeNull().cursor
        first.close()

        val resumed = streams.open(tenant, campaignId, cursor)
        val snapshot = resumed.next(Duration.ZERO).shouldNotBeNull()

        snapshot.event shouldBeEqualTo "snapshot"
        (snapshot.cursor.id >= cursor.id).shouldBeTrue()
        resumed.queueDepth() shouldBeEqualTo 0
        resumed.close()
    }

    @Test
    fun `delayed event fixture leaves a cursor that can resume`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)
        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/delayed-duplicate-out-of-order/run",
            randomIdentifier(),
            mapOf("principalRef" to randomIdentifier(), "campaignId" to campaignId),
        ).exchange().expectStatus().isOk

        val first = streams.open(tenant, campaignId, null)
        val cursor = first.next(Duration.ZERO).shouldNotBeNull().cursor
        first.close()

        val resumed = streams.open(tenant, campaignId, cursor)
        resumed.next(Duration.ZERO).shouldNotBeNull().event shouldBeEqualTo "snapshot"
        resumed.close()
    }

    @Test
    fun `future cursor is rejected before the stream starts`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        webTestClient.get().uri("/api/v1/campaigns/$campaignId/events")
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, randomIdentifier())
            .header("Last-Event-ID", "${Long.MAX_VALUE}:${Long.MAX_VALUE}")
            .exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_EVENT_CURSOR")
    }

    @Test
    fun `future revision without an audit id is rejected`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        webTestClient.get().uri("/api/v1/campaigns/$campaignId/events")
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, randomIdentifier())
            .header("Last-Event-ID", "${Long.MAX_VALUE}:0")
            .exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_EVENT_CURSOR")
    }

    private companion object {
        val STREAM_TIMEOUT: Duration = Duration.ofSeconds(5)
        val HTTP_CLIENT: HttpClient =
            HttpClient.newBuilder()
                .connectTimeout(STREAM_TIMEOUT)
                .build()
    }
}
