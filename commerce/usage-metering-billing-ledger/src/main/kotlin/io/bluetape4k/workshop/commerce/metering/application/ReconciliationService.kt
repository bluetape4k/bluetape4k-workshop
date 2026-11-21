@file:Suppress("TooManyFunctions") // Each function owns one bounded reconciliation invariant.

package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.LedgerEntryType
import io.bluetape4k.workshop.commerce.metering.domain.ReconciliationFindingType
import io.bluetape4k.workshop.commerce.metering.domain.chargeAmount
import io.bluetape4k.workshop.commerce.metering.idempotency.Sha256Digest
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodEntity
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntries
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLines
import io.bluetape4k.workshop.commerce.metering.persistence.Invoices
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryEntity
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionEntity
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationFindingEntity
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationFindings
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationRunEntity
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationRuns
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventEntity
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEvents
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.*
/**
 * ReconciliationResult은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property runId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property findingCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property summaryDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property hasMore 사용량 계량·청구 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */


data class ReconciliationResult(
    /**
     * `runId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val runId: UUID,
    /**
     * `findingCount`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val findingCount: Int,
    /**
     * `summaryDigest`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val summaryDigest: String,
    /**
     * `hasMore`은 사용량 과금·정산에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val hasMore: Boolean,
)
/**
 * FindingCandidate은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property type 현재 사용량 계량·청구 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property resourceType 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property actualDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


private data class FindingCandidate(
    /**
     * `type`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val type: ReconciliationFindingType,
    /**
     * `resourceType`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val resourceType: String,
    /**
     * `resourceId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: String,
    /**
     * `expectedDigest`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val expectedDigest: String?,
    /**
     * `actualDigest`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val actualDigest: String?,
) {
    /**
     * `digestLine`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = digestLine()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun digestLine(): String = "$type|$resourceType|$resourceId|$expectedDigest|$actualDigest"
}
/**
 * ReconciliationService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property properties 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property prices 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 * @property adjustments 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class ReconciliationService(
    /**
     * `properties`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: MeteringProperties,
    /**
     * `prices`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val prices: PriceVersionRepository,
    /**
     * `adjustments`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val adjustments: AdjustmentService,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    private companion object: KLogging() {
        /**
         * `USAGE_EVENT`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        const val USAGE_EVENT = "USAGE_EVENT"
        /**
         * `LEDGER_ENTRY`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        const val LEDGER_ENTRY = "LEDGER_ENTRY"
        /**
         * `SCAN_PAGE_SIZE`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val SCAN_PAGE_SIZE = 200
    }

    /**
     * `inspect`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = inspect(tenantId = "tenant-a", requestedPageSize = 25)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param requestedPageSize 요청 경계에서 검증된 `requestedPageSize`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun inspect(
        tenantId: String,
        requestedPageSize: Int = properties.reconciliation.pageSize,
    ): ReconciliationResult {
        /**
         * `pageSize`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val pageSize = requestedPageSize.coerceAtMost(properties.reconciliation.maxPageSize)
        pageSize.requirePositiveNumber("pageSize")

        /**
         * `now`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val now = clock.instant()
        /**
         * `run`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val run = newRun(tenantId, now)
        /**
         * `candidateLimit`은 사용량 과금·정산에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        val candidateLimit = pageSize + 1
        /**
         * `discovered`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val discovered = buildList {
            addAll(usageFindings(tenantId, candidateLimit - size))
            addAll(ledgerFindings(tenantId, candidateLimit - size))
            addAll(invoiceLineFindings(tenantId, candidateLimit - size))
            addAll(invoiceTotalFindings(tenantId, candidateLimit - size))
        }
        /**
         * `candidates`은 사용량 과금·정산에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        val candidates = discovered.take(pageSize)
        candidates.forEach { candidate ->
            appendFinding(tenantId = tenantId, run.id.value, candidate, now)
        }
        /**
         * `digest`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val digest = Sha256Digest(candidates.joinToString("\n", transform = FindingCandidate::digestLine)).value
        run.findingCount = candidates.size
        run.summaryDigest = digest
        run.completedAt = now

        return ReconciliationResult(
            runId = run.id.value,
            findingCount = candidates.size,
            summaryDigest = digest,
            hasMore = discovered.size > pageSize
        )
    }

    /**
     * `repairLateUsage`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = repairLateUsage(tenantId = "tenant-a", findingId = UUID.randomUUID(),
     * expectedDigest
     * = "demo", currency = "demo")
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param findingId 요청 경계에서 검증된 `findingId`입니다.
     * @param expectedDigest 요청 경계에서 검증된 `expectedDigest`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun repairLateUsage(
        tenantId: String,
        findingId: UUID,
        expectedDigest: String,
        currency: String,
    ): UUID {
        val finding = finding(tenantId, findingId)
            .requireNotNull { "ReconciliationFindingEntity not found. tenantId: $tenantId, findingId: $findingId" }

        finding.findingType.requireEquals(ReconciliationFindingType.UNLEDGERED_USAGE_AFTER_CUTOFF.name) {
            "findType must be UNLEDGERED_USAGE_AFTER_CUTOFF"
        }
        finding.expectedDigest.requireEquals(expectedDigest) {
            "finding.expectedDigest must be equal to expectedDigest"
        }

        val usageId = UUID.fromString(finding.resourceId)
        val usage = UsageEventEntity.findById(usageId)
            .requireNotNull { "UsageEventEntity not found. usageId: $usageId" }
        require(usage.tenantId == tenantId && usageDigest(usage) == expectedDigest) {
            "usage tenantId 또는 usageDigest 가 예상과 다릅니다. tenantId: $tenantId, usage=$usage"
        }

        val existing: LedgerEntryEntity? = LedgerEntryEntity
            .find {
                (LedgerEntries.tenantId eq tenantId) and
                        (LedgerEntries.entryType eq LedgerEntryType.DEBIT_ADJUSTMENT.name) and
                        (LedgerEntries.sourceReferenceType eq USAGE_EVENT) and
                        (LedgerEntries.sourceReferenceId eq usageId.toString())
            }
            .firstOrNull()
        check(existing == null) { "LeagerEntryEntity must not be exists. tenantId: $tenantId, usageId: $usageId" }

        return adjustments.postLateDebit(
            tenantId = tenantId,
            usageEventId = usageId,
            currency = currency
        )
    }

    /**
     * `finding`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = finding(tenantId = "tenant-a", findingId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param findingId 요청 경계에서 검증된 `findingId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional(readOnly = true)
    fun finding(tenantId: String, findingId: UUID): ReconciliationFindingEntity? =
        ReconciliationFindingEntity
            .find {
                (ReconciliationFindings.id eq findingId) and (ReconciliationFindings.tenantId eq tenantId)
            }
            .firstOrNull()

    /**
     * newRun은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun newRun(tenantId: String, now: Instant): ReconciliationRunEntity =
        ReconciliationRunEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId
            scope = "billing-authority"
            startedAt = now
            completedAt = null
            summaryDigest = null
            findingCount = 0
        }

    /**
     * usageFindings은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun usageFindings(tenantId: String, limit: Int): List<FindingCandidate> {
        if (limit <= 0) return emptyList()
        val findings = mutableListOf<FindingCandidate>()
        var cursor: UUID? = null
        var exhausted = false

        while (findings.size < limit && !exhausted) {
            val page = UsageEventEntity
                .find {
                    val tenant = UsageEvents.tenantId eq tenantId
                    cursor?.let { tenant and (UsageEvents.id greater it) } ?: tenant
                }
                .orderBy(UsageEvents.id to SortOrder.ASC)
                .limit(SCAN_PAGE_SIZE)
                .toList()

            page.lastOrNull()?.let { cursor = it.id.value }
            page.mapNotNullTo(findings) { usage ->
                val servicePeriod = BillingPeriodEntity
                    .find {
                        (BillingPeriods.tenantId eq tenantId) and
                                (BillingPeriods.state eq BillingPeriodState.FINALIZED.name) and
                                (BillingPeriods.startsAt lessEq usage.occurredAt) and
                                (BillingPeriods.endsAt greater usage.occurredAt)
                    }
                    .firstOrNull() ?: return@mapNotNullTo null

                val hasLedger = LedgerEntries
                    .selectAll()
                    .where {
                        (LedgerEntries.tenantId eq tenantId) and
                                (LedgerEntries.sourceReferenceType eq USAGE_EVENT) and
                                (LedgerEntries.sourceReferenceId eq usage.id.value.toString())
                    }.limit(1)
                    .any()
                if (hasLedger) return@mapNotNullTo null

                val cutoffReceivedAt = checkNotNull(servicePeriod.cutoffReceivedAt) {
                    "service period cutoffReceivedAt must be present"
                }
                val type = if (usage.receivedAt > cutoffReceivedAt) {
                    ReconciliationFindingType.UNLEDGERED_USAGE_AFTER_CUTOFF
                } else {
                    ReconciliationFindingType.UNLEDGERED_USAGE
                }
                FindingCandidate(
                    type = type,
                    resourceType = USAGE_EVENT,
                    resourceId = usage.id.value.toString(),
                    expectedDigest = usageDigest(usage),
                    actualDigest = null
                )
            }
            exhausted = page.size < SCAN_PAGE_SIZE
        }
        return findings.take(limit)
    }

    /**
     * ledgerFindings은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun ledgerFindings(tenantId: String, limit: Int): List<FindingCandidate> {
        if (limit <= 0) return emptyList()
        val findings = mutableListOf<FindingCandidate>()
        var cursor: UUID? = null
        var exhausted = false
        while (findings.size < limit && !exhausted) {
            val page = LedgerEntryEntity
                .find {
                    val tenant = LedgerEntries.tenantId eq tenantId
                    cursor?.let { tenant and (LedgerEntries.id greater it) } ?: tenant
                }
                .orderBy(LedgerEntries.id to SortOrder.ASC)
                .limit(SCAN_PAGE_SIZE)
                .toList()
            page.lastOrNull()?.let { cursor = it.id.value }
            page.flatMapTo(findings) { entry ->
                listOfNotNull(priceFinding(tenantId, entry), authorityFinding(entry))
            }
            exhausted = page.size < SCAN_PAGE_SIZE
        }
        return findings.take(limit)
    }

    /**
     * priceFinding은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param entry 업무 처리에 필요한 entry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Suppress("ReturnCount") // Fast exits keep the charge-only comparison explicit.
    private fun priceFinding(tenantId: String, entry: LedgerEntryEntity): FindingCandidate? {
        if (entry.entryType != LedgerEntryType.CHARGE.name || entry.sourceReferenceType != USAGE_EVENT) return null

        val usage = UsageEventEntity
            .findById(UUID.fromString(entry.sourceReferenceId)) ?: return null
        val expected = prices
            .select(tenantId, usage.meterId.value, entry.currency, usage.occurredAt) ?: return null
        val expectedAmount = chargeAmount(usage.quantity, expected.unitPrice, entry.currency)

        if (entry.priceVersionId.value == expected.id.value &&
            entry.unitPrice.compareTo(expected.unitPrice) == 0 &&
            entry.amount.compareTo(expectedAmount) == 0
        ) {
            return null
        }
        return FindingCandidate(
            type = ReconciliationFindingType.LEDGER_PRICE_MISMATCH,
            resourceType = LEDGER_ENTRY,
            resourceId = entry.id.value.toString(),
            expectedDigest = Sha256Digest.of("${expected.id.value}|${expected.unitPrice}|$expectedAmount").value,
            actualDigest = ledgerDigest(entry),
        )
    }

    /**
     * authorityFinding은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entry 업무 처리에 필요한 entry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Suppress("ReturnCount") // Missing authority rows are reported through the same immutable finding.
    private fun authorityFinding(entry: LedgerEntryEntity): FindingCandidate? {
        val posting = BillingPeriodEntity.findById(entry.postingPeriodId.value) ?: return mismatch(entry)
        val service = BillingPeriodEntity.findById(entry.servicePeriodId.value) ?: return mismatch(entry)
        val price = PriceVersionEntity.findById(entry.priceVersionId.value) ?: return mismatch(entry)

        val tenantMatches = setOf(entry.tenantId, posting.tenantId, service.tenantId, price.tenantId).size == 1
        val currencyMatches = setOf(entry.currency, posting.currency, service.currency, price.currency).size == 1

        return if (tenantMatches && currencyMatches) null else mismatch(entry)
    }

    /**
     * mismatch은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entry 업무 처리에 필요한 entry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun mismatch(entry: LedgerEntryEntity): FindingCandidate =
        FindingCandidate(
            type = ReconciliationFindingType.TENANT_OR_CURRENCY_MISMATCH,
            resourceType = LEDGER_ENTRY,
            resourceId = entry.id.value.toString(),
            expectedDigest = Sha256Digest.of("${entry.tenantId}|${entry.currency}").value,
            actualDigest = ledgerDigest(entry),
        )

    /**
     * invoiceLineFindings은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun invoiceLineFindings(tenantId: String, limit: Int): List<FindingCandidate> {
        if (limit <= 0) return emptyList()
        val findings = mutableListOf<FindingCandidate>()
        var cursor: UUID? = null
        var exhausted = false

        while (findings.size < limit && !exhausted) {
            val page = InvoiceLineEntity
                .find {
                    val tenant = InvoiceLines.tenantId eq tenantId
                    cursor?.let { tenant and (InvoiceLines.id greater it) } ?: tenant
                }
                .orderBy(InvoiceLines.id to SortOrder.ASC)
                .limit(SCAN_PAGE_SIZE)
                .toList()

            page.lastOrNull()?.let { cursor = it.id.value }
            page.mapNotNullTo(findings) { line ->
                val entries = InvoiceLineEntries
                    .selectAll()
                    .where { InvoiceLineEntries.invoiceLineId eq line.id.value }
                    .mapNotNull { LedgerEntryEntity.findById(it[InvoiceLineEntries.ledgerEntryId].value) }

                val expectedQuantity = entries
                    .fold(BigDecimal.ZERO) { sum, entry ->
                        sum + entry.quantity
                    }
                val expectedAmount = entries
                    .fold(BigDecimal.ZERO) { sum, entry ->
                        sum + entry.amount
                    }

                val expected = Sha256Digest(
                    "$expectedQuantity|$expectedAmount|${line.currency}|${entries.size}",
                ).value
                val actual = Sha256Digest(
                    "${line.quantity}|${line.amount}|${line.currency}|${entries.size}"
                ).value

                if (expected == actual) null
                else FindingCandidate(
                    type = ReconciliationFindingType.INVOICE_LINE_MISMATCH,
                    resourceType = "INVOICE_LINE",
                    resourceId = line.id.value.toString(),
                    expectedDigest = expected,
                    actualDigest = actual,
                )
            }
            exhausted = page.size < SCAN_PAGE_SIZE
        }
        return findings.take(limit)
    }

    /**
     * invoiceTotalFindings은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun invoiceTotalFindings(tenantId: String, limit: Int): List<FindingCandidate> {
        if (limit <= 0) return emptyList()
        val findings = mutableListOf<FindingCandidate>()
        var cursor: UUID? = null
        var exhausted = false

        while (findings.size < limit && !exhausted) {
            val page = InvoiceEntity
                .find {
                    val tenant = Invoices.tenantId eq tenantId
                    cursor?.let { tenant and (Invoices.id greater it) } ?: tenant
                }
                .orderBy(Invoices.id to SortOrder.ASC)
                .limit(SCAN_PAGE_SIZE)
                .toList()

            page.lastOrNull()?.let { cursor = it.id.value }

            page.mapNotNullTo(findings) { invoice ->
                val lineTotal = InvoiceLineEntity
                    .find { InvoiceLines.invoiceId eq invoice.id.value }
                    .fold(BigDecimal.ZERO) { sum, line ->
                        sum + line.amount
                    }

                if (lineTotal.compareTo(invoice.totalAmount) == 0) null
                else FindingCandidate(
                    type = ReconciliationFindingType.INVOICE_TOTAL_MISMATCH,
                    resourceType = "INVOICE",
                    resourceId = invoice.id.value.toString(),
                    expectedDigest = Sha256Digest.of(lineTotal.toPlainString()).value,
                    actualDigest = Sha256Digest.of(invoice.totalAmount.toPlainString()).value,
                )
            }
            exhausted = page.size < SCAN_PAGE_SIZE
        }
        return findings.take(limit)
    }

    /**
     * appendFinding은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
     * 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param runId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param candidate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun appendFinding(
        tenantId: String,
        runId: UUID,
        candidate: FindingCandidate,
        now: Instant,
    ) {
        ReconciliationFindingEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId
            this.runId = EntityID(runId, ReconciliationRuns)
            findingType = candidate.type.name
            resourceType = candidate.resourceType
            resourceId = candidate.resourceId
            expectedDigest = candidate.expectedDigest
            actualDigest = candidate.actualDigest
            createdAt = now
        }
    }

    /**
     * usageDigest은 사용량 계량·청구 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param usage 업무 처리에 필요한 usage 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun usageDigest(usage: UsageEventEntity): String =
        Sha256Digest(
            "${usage.id.value}|${usage.tenantId}|${usage.meterId.value}|${usage.quantity}|" +
                    "${usage.occurredAt}|${usage.receivedAt}",
        ).value

    /**
     * ledgerDigest은 사용량 계량·청구 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entry 업무 처리에 필요한 entry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun ledgerDigest(entry: LedgerEntryEntity): String =
        Sha256Digest(
            "${entry.id.value}|${entry.tenantId}|${entry.priceVersionId.value}|${entry.quantity}|" +
                    "${entry.unitPrice}|${entry.amount}|${entry.currency}",
        ).value
}
