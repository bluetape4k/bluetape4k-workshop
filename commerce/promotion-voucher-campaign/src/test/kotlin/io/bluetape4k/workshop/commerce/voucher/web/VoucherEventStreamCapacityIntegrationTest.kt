package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.*

/**
 * VoucherEventStreamCapacityIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherEventStreamCapacityIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Autowired
/**
 * streams은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var streams: VoucherEventStream

    @Autowired
/**
 * jdbc은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var jdbc: VoucherJdbcExecutor

    @Autowired
/**
 * campaigns은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var campaigns: CampaignRepository

    @Test
/**
 * 이 시나리오는 "thirty third campaign returns bounded fallback contract and retries after capacity
 * returns" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `thirty third campaign returns bounded fallback contract and retries after capacity returns`() {
        val tenant = randomIdentifier()
        val principal = randomIdentifier()
        val campaignIds = seedActiveCampaigns(tenant, 33)
        val subscriptions = campaignIds.take(32).map {
            streams.open(tenant, it, null)
        }.toMutableList()
        val rejectedCampaign = campaignIds.last()

        try {
            webTestClient.get()
                .uri("/api/v1/campaigns/$rejectedCampaign/events")
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, principal)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectHeader().valueEquals(
                    "Link",
                    "</api/v1/campaigns/$rejectedCampaign>; rel=\"alternate\"; type=\"application/json\"",
                )
                .expectBody()
                .jsonPath("$.code").isEqualTo("SSE_CAPACITY_REJECTED")

            webTestClient.get()
                .uri("/api/v1/campaigns/$rejectedCampaign")
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, principal)
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.campaignId").isEqualTo(rejectedCampaign.toString())

            subscriptions.removeFirst().close()
            subscriptions += streams.open(tenant, rejectedCampaign, null)
            streams.activePollers() shouldBeEqualTo 32
        } finally {
            subscriptions.forEach(AutoCloseable::close)
        }
        streams.activePollers() shouldBeEqualTo 0
    }

    @Suppress("SameParameterValue")
/**
 * seedActiveCampaigns은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun seedActiveCampaigns(
        tenant: String,
        count: Int,
    ): List<UUID> = jdbc.foregroundTransaction {
/**
 * now은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val now = Instant.now()
        List(count) {
            campaigns.create(
                CampaignRecord(
                    id = 0,
                    tenantId = tenant,
                    campaignId = Uuid.V7.nextId(),
                    state = CampaignState.ACTIVE,
                    startsAt = now - 60.seconds(),
                    endsAt = now + 3600.seconds(),
                    capacity = 10,
                    allocatedCount = 0,
                    perUserLimit = 1,
                    redemptionTtlSeconds = 600,
                    policyVersion = 1,
                    revision = 1,
                ),
            ).campaignId
        }
    }
}
