package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.config.VoucherProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherSseProperties
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * VoucherEventStreamBatchBoundIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherEventStreamBatchBoundIntegrationTest: AbstractVoucherIntegrationTest() {

    private companion object: KLogging() {
/**
 * SSE_FRAME_BYTES은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SSE_FRAME_BYTES = 32
    }

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

    @Autowired
/**
 * audits은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var audits: AuditRepository

    @Autowired
/**
 * mapper은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var mapper: ObjectMapper

    @Autowired
/**
 * permits은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var permits: DatabasePermitGate

    @Test
/**
 * 이 시나리오는 "poll batch stops at row and encoded payload boundaries then returns database permits"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `poll batch stops at row and encoded payload boundaries then returns database permits`() {
        val tenant = randomIdentifier()
        val campaignId = Uuid.V7.nextId()
        seedCampaignWithAudits(tenant, campaignId, 10)

        val rowBoundSource = source(maxRows = 3, maxPayloadBytes = 256 * 1024)
        val rowBound = rowBoundSource.poll(tenant, campaignId, 0)
        rowBound.events.size shouldBeEqualTo 3

        val oneEncodedEventBytes = rowBound.events.first()
            .let { (cursor, event) ->
                mapper.writeValueAsBytes(event).size + cursor.toString().length + SSE_FRAME_BYTES
            }
        val payloadLimit = oneEncodedEventBytes * 2 + oneEncodedEventBytes / 2
        val payloadBound = source(maxRows = 200, maxPayloadBytes = payloadLimit)
            .poll(tenant, campaignId, 0)

        payloadBound.events.size shouldBeEqualTo 2
        val payloadSum = payloadBound.events.sumOf { (cursor, event) ->
            mapper.writeValueAsBytes(event).size + cursor.toString().length + SSE_FRAME_BYTES
        }
        payloadSum shouldBeLessOrEqualTo payloadLimit

        permits.availablePermits(DatabaseLane.SSE_MAINTENANCE) shouldBeEqualTo 3
        permits.availablePermits(DatabaseLane.WORKER) shouldBeEqualTo 1
    }

/**
 * source은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun source(
        maxRows: Int,
        maxPayloadBytes: Int,
    ): PostgresVoucherEventSource =
        PostgresVoucherEventSource(
            jdbc = jdbc,
            campaigns = campaigns,
            audits = audits,
            mapper = mapper,
            properties = VoucherProperties(
                sse = VoucherSseProperties(maxRows = maxRows, maxPayloadBytes = maxPayloadBytes),
            ),
        )

/**
 * seedCampaignWithAudits은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun seedCampaignWithAudits(
        tenant: String,
        campaignId: java.util.UUID,
        count: Int,
    ) {
/**
 * now은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val now = Instant.now()
        jdbc.foregroundTransaction {
            campaigns.create(
                CampaignRecord(
                    id = 0,
                    tenantId = tenant,
                    campaignId = campaignId,
                    state = CampaignState.ACTIVE,
                    startsAt = now.minusSeconds(60),
                    endsAt = now.plusSeconds(3600),
                    capacity = 10,
                    allocatedCount = 0,
                    perUserLimit = 1,
                    redemptionTtlSeconds = 600,
                    policyVersion = 1,
                    revision = count.toLong(),
                ),
            )
            (1..count).forEach { revision ->
                audits.append(
                    AuditRecord(
                        id = 0,
                        tenantId = tenant,
                        campaignId = campaignId,
                        aggregateType = "CAMPAIGN",
                        aggregateId = campaignId,
                        revision = revision.toLong(),
                        actorType = "OPERATOR",
                        reasonCode = "R".repeat(64),
                        policyVersion = 1,
                        correlationDigest = null,
                    ),
                )
            }
        }
    }
}
