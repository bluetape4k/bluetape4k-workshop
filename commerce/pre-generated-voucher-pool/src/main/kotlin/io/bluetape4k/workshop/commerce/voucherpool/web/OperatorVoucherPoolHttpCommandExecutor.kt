@file:Suppress("LongParameterList", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.workshop.commerce.voucherpool.application.BatchCommandException
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchCommandFailure
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSourceKind
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateImportBatchCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.GenerateChunkCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ImportChunkCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.MutationResult
import io.bluetape4k.workshop.commerce.voucherpool.application.ReconciliationCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ReconciliationCommandException
import io.bluetape4k.workshop.commerce.voucherpool.application.ReconciliationCommandFailure
import io.bluetape4k.workshop.commerce.voucherpool.application.ReconciliationProgressSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.RevocationCommandException
import io.bluetape4k.workshop.commerce.voucherpool.application.RevocationCommandFailure
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokeAggregateType
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokeCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokePreviewSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokeProgressSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.UpdateCampaignPolicyCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolReconciliationCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolRevocationService
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.query.VoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.net.URI
import java.util.*
import kotlin.time.Duration.Companion.seconds

/**
 * operator HTTP precondition을 tenant-scoped campaign 및 batch command로 매핑합니다.
 * @property service 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property queries 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property revocations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reconciliations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class OperatorVoucherPoolHttpCommandExecutor(
    /**
     * `service`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: CampaignBatchCommandService,
    /**
     * `queries`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val queries: VoucherPoolQueryService,
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
    /**
     * `revocations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val revocations: VoucherPoolRevocationService,
    /**
     * `reconciliations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reconciliations: VoucherPoolReconciliationCommandService,
) {
    /**
     * createCampaign은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifNoneMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun createCampaign(
        tenantId: String,
        idempotencyKey: String,
        ifNoneMatch: String,
        request: OperatorCreateCampaignRequest,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> = translate {
        requireCreatePrecondition(ifNoneMatch)
        val result: MutationResult<CampaignSnapshot> = service.createCampaign(
            command = CreateCampaignCommand(
                tenantId = tenantId,
                campaignId = request.campaignId,
                startsAt = request.startsAt,
                endsAt = request.endsAt,
                policy = request.policy(),
                idempotencyKey = idempotencyKey,
            ),
        )
        campaignResult(tenantId, request.campaignId, result, requestId, created = true)
    }

    /**
     * updateCampaignPolicy은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun updateCampaignPolicy(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorCampaignPolicyRequest,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> = translate {
        val result = service.updatePolicy(
            UpdateCampaignPolicyCommand(
                tenantId = tenantId,
                campaignId = campaignId,
                expectedRevision = parseRevision(ifMatch),
                policy = request.policy(),
                idempotencyKey = idempotencyKey,
            ),
        )
        campaignResult(tenantId, campaignId, result, requestId)
    }

    /**
     * activateCampaign은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun activateCampaign(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> = translate {
        val result = service.activateCampaign(
            CampaignRevisionCommand(
                tenantId = tenantId,
                campaignId = campaignId,
                expectedRevision = parseRevision(ifMatch),
                idempotencyKey = idempotencyKey
            ),
        )
        campaignResult(tenantId, campaignId, result, requestId)
    }

    /**
     * pauseCampaign은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun pauseCampaign(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> =
        campaignTransition(
            tenantId = tenantId,
            campaignId = campaignId,
            idempotencyKey = idempotencyKey,
            ifMatch = ifMatch,
            requestId = requestId,
            transition = service::pauseCampaign,
        )

    /**
     * resumeCampaign은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun resumeCampaign(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> =
        campaignTransition(
            tenantId = tenantId,
            campaignId = campaignId,
            idempotencyKey = idempotencyKey,
            ifMatch = ifMatch,
            requestId = requestId,
            transition = service::resumeCampaign,
        )

    /**
     * createBatch은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifNoneMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param imported 업무 처리에 필요한 imported 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun createBatch(
        tenantId: String,
        idempotencyKey: String,
        ifNoneMatch: String,
        request: OperatorCreateBatchRequest,
        imported: Boolean,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> = translate {
        requireCreatePrecondition(ifNoneMatch)
        if (!imported && request.codes.isNotEmpty()) throw invalidRequest()
        val sourceKind = if (imported) BatchSourceKind.IMPORTED else BatchSourceKind.GENERATED
        val manifest = request.manifestDigest.digestValue()
        val requestFingerprint = serverRequestFingerprint(tenantId, sourceKind, request)

        val result = service.createImportBatch(
            CreateImportBatchCommand(
                tenantId = tenantId,
                batchId = request.batchId,
                campaignId = request.campaignId,
                sourceKind = sourceKind,
                manifestDigest = manifest,
                requestFingerprint = requestFingerprint,
                expectedCount = request.expectedCount,
                activatesAt = request.activatesAt,
                expiresAt = request.expiresAt,
                initialCodes = request.codes,
                idempotencyKey = idempotencyKey,
            ),
        )
        batchResult(tenantId, request.batchId, result, requestId, created = true)
    }

    /**
     * importChunk은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun importChunk(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorImportChunkRequest,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> = translate {
        val result = service.importChunk(
            ImportChunkCommand(
                tenantId = tenantId,
                batchId = batchId,
                campaignId = request.campaignId,
                firstOrdinal = request.firstOrdinal,
                manifestDigest = request.manifestDigest.digestValue(),
                codes = request.codes,
                expectedRevision = parseRevision(ifMatch),
                idempotencyKey = idempotencyKey,
            ),
        )
        batchResult(tenantId, batchId, result, requestId)
    }

    /**
     * generateChunk은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun generateChunk(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorGenerateChunkRequest,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> =
        translate {
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = service.generateChunk(
                GenerateChunkCommand(
                    tenantId = tenantId,
                    batchId = batchId,
                    campaignId = request.campaignId,
                    firstOrdinal = request.firstOrdinal,
                    manifestDigest = request.manifestDigest.digestValue(),
                    count = request.count,
                    expectedRevision = parseRevision(ifMatch),
                    idempotencyKey = idempotencyKey,
                ),
            )
            batchResult(tenantId, batchId, result, requestId)
        }

    /**
     * activateBatch은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun activateBatch(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> = translate {
        val batch = queries.batch(tenantId, batchId) ?: throw resourceNotFound()
        val result = service.activateBatch(
            BatchRevisionCommand(tenantId, batch.campaignId, batchId, parseRevision(ifMatch), idempotencyKey),
        )
        batchResult(tenantId, batchId, result, requestId)
    }

    /**
     * pauseBatch은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun pauseBatch(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> =
        batchTransition(tenantId, batchId, idempotencyKey, ifMatch, requestId, service::pauseBatch)

    /**
     * resumeBatch은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun resumeBatch(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> =
        batchTransition(tenantId, batchId, idempotencyKey, ifMatch, requestId, service::resumeBatch)

    /**
     * previewCampaignRevoke은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게
     * 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun previewCampaignRevoke(
        tenantId: String,
        campaignId: UUID,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorRevokePreviewResponse> = translate {
        val preview = revocations.preview(
            tenantId,
            RevokeAggregateType.CAMPAIGN,
            campaignId,
            parseRevision(ifMatch),
        )
        ResponseEntity.ok()
            .eTag(operatorEtag(preview.revision))
            .body(preview.toResponse(requestId))
    }

    /**
     * previewBatchRevoke은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun previewBatchRevoke(
        tenantId: String,
        batchId: UUID,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorRevokePreviewResponse> = translate {
        val preview = revocations.preview(
            tenantId = tenantId,
            aggregateType = RevokeAggregateType.BATCH,
            aggregateId = batchId,
            expectedRevision = parseRevision(ifMatch)
        )
        ResponseEntity.ok()
            .eTag(operatorEtag(preview.revision))
            .body(preview.toResponse(requestId))
    }

    /**
     * revokeCampaign은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun revokeCampaign(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorCampaignRevokeRequest,
        requestId: String,
    ): ResponseEntity<OperatorWorkerProgressResponse> =
        revoke(
            tenantId = tenantId,
            aggregateType = RevokeAggregateType.CAMPAIGN,
            aggregateId = campaignId,
            confirmedAggregateId = request.confirmedCampaignId,
            previewToken = request.previewToken,
            idempotencyKey = idempotencyKey,
            ifMatch = ifMatch,
            requestId = requestId,
        )

    /**
     * revokeBatch은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun revokeBatch(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorBatchRevokeRequest,
        requestId: String,
    ): ResponseEntity<OperatorWorkerProgressResponse> =
        revoke(
            tenantId = tenantId,
            aggregateType = RevokeAggregateType.BATCH,
            aggregateId = batchId,
            confirmedAggregateId = request.confirmedBatchId,
            previewToken = request.previewToken,
            idempotencyKey = idempotencyKey,
            ifMatch = ifMatch,
            requestId = requestId,
        )

    /**
     * revoke은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param aggregateType 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param aggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param confirmedAggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param previewToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun revoke(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        confirmedAggregateId: UUID,
        previewToken: String,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorWorkerProgressResponse> =
        translate {
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = revocations.revoke(
                RevokeCommand(
                    tenantId = tenantId,
                    aggregateType = aggregateType,
                    aggregateId = aggregateId,
                    confirmedAggregateId = confirmedAggregateId,
                    expectedRevision = parseRevision(ifMatch),
                    previewToken = previewToken,
                    idempotencyKey = idempotencyKey,
                ),
            )
            /**
             * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
             *
             * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
             */
            val outcome = result.commandOutcome()
            /**
             * `progress`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val progress = when (result) {
                is MutationResult.Applied -> result.value
                is MutationResult.Replay  ->
                    revocations.progress(tenantId, aggregateType, aggregateId)
                        ?: throw resourceNotFound()
                is MutationResult.Expired ->
                    throw apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED, result.effectId)
            }
            /**
             * `builder`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val builder = ResponseEntity.status(if (outcome.replayed) HttpStatus.OK else HttpStatus.ACCEPTED)

            builder
                .eTag(operatorEtag(progress.revision))
                .operatorCommandHeaders(outcome.replayed)
                .body(progress.toResponse(requestId))
        }

    /**
     * runReconciliation은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun runReconciliation(
        tenantId: String,
        idempotencyKey: String,
        request: OperatorReconciliationRequest,
        requestId: String,
    ): ResponseEntity<OperatorReconciliationProgressResponse> =
        translate {
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = reconciliations.run(ReconciliationCommand(tenantId, request.batchId, idempotencyKey))
            /**
             * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
             *
             * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
             */
            val outcome = result.commandOutcome()
            /**
             * `progress`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val progress = when (result) {
                is MutationResult.Applied -> result.value
                is MutationResult.Replay  -> reconciliations.progress(tenantId, request.batchId)
                    ?: throw resourceNotFound()
                is MutationResult.Expired -> throw apiFailure(
                    VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED,
                    result.effectId
                )
            }
            ResponseEntity
                .status(if (outcome.replayed) HttpStatus.OK else HttpStatus.ACCEPTED)
                .operatorCommandHeaders(outcome.replayed)
                .body(progress.toResponse(requestId))
        }

    /**
     * campaignTransition은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param transition 업무 처리에 필요한 transition 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun campaignTransition(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
        transition: (CampaignRevisionCommand) -> MutationResult<CampaignSnapshot>,
    ): ResponseEntity<OperatorCampaignResponse> =
        translate {
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = transition(
                CampaignRevisionCommand(tenantId, campaignId, parseRevision(ifMatch), idempotencyKey),
            )
            campaignResult(tenantId, campaignId, result, requestId)
        }

    /**
     * batchTransition은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param transition 업무 처리에 필요한 transition 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun batchTransition(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
        transition: (BatchRevisionCommand) -> MutationResult<BatchSnapshot>,
    ): ResponseEntity<OperatorBatchResponse> =
        translate {
            /**
             * `batch`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val batch = queries.batch(tenantId, batchId) ?: throw resourceNotFound()
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = transition(
                BatchRevisionCommand(tenantId, batch.campaignId, batchId, parseRevision(ifMatch), idempotencyKey),
            )
            batchResult(tenantId, batchId, result, requestId)
        }

    /**
     * campaignResult은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param created 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun campaignResult(
        tenantId: String,
        campaignId: UUID,
        result: MutationResult<CampaignSnapshot>,
        requestId: String,
        created: Boolean = false,
    ): ResponseEntity<OperatorCampaignResponse> {
        /**
         * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val outcome = result.commandOutcome()
        /**
         * `read`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val read = queries.campaign(tenantId, campaignId) ?: throw resourceNotFound()
        /**
         * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val response = read.toOperatorResponse(requestId)

        /**
         * `builder`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val builder = if (created && !outcome.replayed) {
            ResponseEntity.created(URI.create("/operator/api/v1/campaigns/$campaignId"))
        } else {
            ResponseEntity.status(HttpStatus.OK)
        }

        return builder
            .eTag(operatorEtag(response.revision))
            .operatorCommandHeaders(outcome.replayed)
            .body(response)
    }

    /**
     * batchResult은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param created 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun batchResult(
        tenantId: String,
        batchId: UUID,
        result: MutationResult<BatchSnapshot>,
        requestId: String,
        created: Boolean = false,
    ): ResponseEntity<OperatorBatchResponse> {
        /**
         * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val outcome = result.commandOutcome()
        /**
         * `read`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val read = queries.batch(tenantId, batchId) ?: throw resourceNotFound()
        /**
         * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val response = read.toOperatorResponse(requestId)
        /**
         * `builder`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val builder = if (created && !outcome.replayed) {
            ResponseEntity.created(URI.create("/operator/api/v1/batches/$batchId"))
        } else {
            ResponseEntity.status(HttpStatus.OK)
        }
        return builder
            .eTag(operatorEtag(response.revision))
            .operatorCommandHeaders(outcome.replayed)
            .body(response)
    }

    /**
     * serverRequestFingerprint은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param sourceKind 업무 처리에 필요한 sourceKind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun serverRequestFingerprint(
        tenantId: String,
        sourceKind: BatchSourceKind,
        request: OperatorCreateBatchRequest,
    ): DigestValue {
        val material = listOf(
            request.batchId,
            request.campaignId,
            sourceKind,
            request.manifestDigest.lowercase(),
            request.expectedCount,
            request.activatesAt,
            request.expiresAt ?: "",
            request.codes.size,
        ).joinToString("|")

        val bytes = digests.audit(tenantId, "operator-batch-create", material).copyBytes()
        return try {
            DigestValue(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * commandOutcome은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun <T> MutationResult<T>.commandOutcome(): CommandOutcome = when (this) {
        is MutationResult.Applied -> CommandOutcome(false)
        is MutationResult.Replay -> {
            descriptor.terminalCode?.let { throw apiFailure(it) }
            CommandOutcome(true)
        }
        is MutationResult.Expired -> throw apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED, effectId)
    }

    /**
     * translate은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Suppress("SwallowedException")
    private inline fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (failure: VoucherPoolApiException) {
            throw failure
        } catch (failure: BatchCommandException) {
            throw failure.toApiException()
        } catch (failure: RevocationCommandException) {
            throw failure.toApiException()
        } catch (failure: ReconciliationCommandException) {
            throw failure.toApiException()
        } catch (_: VoucherPoolJdbcTimeoutException) {
            throw apiFailure(VoucherPoolErrorCode.BACKEND_TIMEOUT)
        } catch (_: IllegalArgumentException) {
            throw invalidRequest()
        }
    /**
     * CommandOutcome은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property replayed 프로모션 바우처 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */


    private class CommandOutcome(val replayed: Boolean)

}

/**
 * policy은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun OperatorCreateCampaignRequest.policy(): VoucherPoolPolicy =
    VoucherPoolPolicy(
        perUserLimit = perUserLimit,
        reservationTtl = reservationTtlSeconds.seconds,
        allocationTtl = allocationTtlSeconds.seconds,
        replacementAllowance = replacementAllowance,
    )

/**
 * policy은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun OperatorCampaignPolicyRequest.policy(): VoucherPoolPolicy =
    VoucherPoolPolicy(
        perUserLimit = perUserLimit,
        reservationTtl = reservationTtlSeconds.seconds,
        allocationTtl = allocationTtlSeconds.seconds,
        replacementAllowance = replacementAllowance,
    )

/**
 * requireCreatePrecondition은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
 * 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param ifNoneMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun requireCreatePrecondition(ifNoneMatch: String) {
    if (ifNoneMatch != "*") throw invalidRequest()
}

/**
 * parseRevision은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
 * 분류합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param ifMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun parseRevision(ifMatch: String): Long {
    val match = OPERATOR_STRONG_ETAG.matchEntire(ifMatch) ?: throw invalidRequest()
    return match.groupValues[1].toLongOrNull() ?: throw invalidRequest()
}

/**
 * digestValue은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun String.digestValue(): DigestValue {
    if (!matches(HEX_DIGEST)) throw invalidRequest()

    val bytes = ByteArray(length / HEX_OCTET_CHARACTERS) { index ->
        val offset = index * HEX_OCTET_CHARACTERS
        substring(offset, offset + HEX_OCTET_CHARACTERS).toInt(HEX_RADIX).toByte()
    }
    return try {
        DigestValue(bytes)
    } finally {
        bytes.fill(0)
    }
}

/**
 * toApiException은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun BatchCommandException.toApiException(): VoucherPoolApiException = when (reason) {
    BatchCommandFailure.SCOPE_NOT_FOUND       -> resourceNotFound()
    BatchCommandFailure.STALE_REVISION        -> apiFailure(VoucherPoolErrorCode.STALE_REVISION)
    BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT,
    BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT,
    BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT,
                                              -> apiFailure(VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT)
    BatchCommandFailure.COMMAND_IN_PROGRESS   -> apiFailure(VoucherPoolErrorCode.COMMAND_IN_PROGRESS)
    BatchCommandFailure.REPLAY_WINDOW_EXPIRED -> apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED)
    BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION -> apiFailure(VoucherPoolErrorCode.POOL_BUSY)
    BatchCommandFailure.DUPLICATE_CODE,
    BatchCommandFailure.VALIDATION_REJECTED,
                                              -> apiFailure(VoucherPoolErrorCode.BATCH_FAILED_TERMINAL)
    BatchCommandFailure.INVALID_STATE,
    BatchCommandFailure.ACTIVATION_INCOMPLETE,
                                              -> VoucherPoolApiException(
        "INVALID_STATE",
        HTTP_CONFLICT,
        "resource state does not permit this operation"
    )
    BatchCommandFailure.ORDINAL_GAP           -> invalidRequest()
}

/**
 * toApiException은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun RevocationCommandException.toApiException(): VoucherPoolApiException = when (reason) {
    RevocationCommandFailure.SCOPE_NOT_FOUND       -> resourceNotFound()
    RevocationCommandFailure.STALE_REVISION        -> apiFailure(VoucherPoolErrorCode.STALE_REVISION)
    RevocationCommandFailure.COMMAND_IN_PROGRESS   -> apiFailure(VoucherPoolErrorCode.COMMAND_IN_PROGRESS)
    RevocationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT ->
        apiFailure(VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT)
    RevocationCommandFailure.REPLAY_WINDOW_EXPIRED -> apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED)
    RevocationCommandFailure.INVALID_STATE         ->
        VoucherPoolApiException(
            "INVALID_STATE",
            HTTP_CONFLICT,
            "resource state does not permit this operation"
        )
    RevocationCommandFailure.INVALID_PREVIEW       -> invalidRequest()
}

/**
 * toApiException은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ReconciliationCommandException.toApiException(): VoucherPoolApiException = when (reason) {
    ReconciliationCommandFailure.SCOPE_NOT_FOUND                  -> resourceNotFound()
    ReconciliationCommandFailure.COMMAND_IN_PROGRESS              -> apiFailure(VoucherPoolErrorCode.COMMAND_IN_PROGRESS)
    ReconciliationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT -> apiFailure(VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT)
    ReconciliationCommandFailure.REPLAY_WINDOW_EXPIRED            -> apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED)
}

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun RevokePreviewSnapshot.toResponse(requestId: String): OperatorRevokePreviewResponse =
    OperatorRevokePreviewResponse(
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        revision = revision,
        counts = counts,
        eligibleDepth = eligibleDepth,
        activeReservations = activeReservations,
        activeAllocations = activeAllocations,
        alreadyTerminalCount = alreadyTerminalCount,
        affectedCount = affectedCount,
        previewToken = previewToken,
        expiresAt = expiresAt,
        observedAt = observedAt,
        requestId = requestId,
    )

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun RevokeProgressSnapshot.toResponse(requestId: String): OperatorWorkerProgressResponse =
    OperatorWorkerProgressResponse(
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        state = state,
        revision = revision,
        workerCount = workerCount,
        observedAt = observedAt,
        requestId = requestId,
    )

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun ReconciliationProgressSnapshot.toResponse(requestId: String): OperatorReconciliationProgressResponse =
    OperatorReconciliationProgressResponse(
        kind = kind,
        scopeId = scopeId,
        state = state,
        cursor = cursor,
        checkpoint = checkpoint,
        attempt = attempt,
        nextAction = nextAction,
        revision = revision,
        observedAt = observedAt,
        requestId = requestId,
    )

/**
 * operatorEtag은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun operatorEtag(revision: Long): String = "\"$revision\""

/**
 * operatorCommandHeaders은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun ResponseEntity.BodyBuilder.operatorCommandHeaders(replayed: Boolean): ResponseEntity.BodyBuilder =
    header("Idempotency-Replay-Window", "86400")
        .apply {
            if (replayed) header("Duplicate-Request", "true")
        }

/**
 * `HEX_DIGEST`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private val HEX_DIGEST = Regex("[0-9a-fA-F]{64}")
/**
 * `OPERATOR_STRONG_ETAG`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private val OPERATOR_STRONG_ETAG = Regex("\"(0|[1-9][0-9]*)\"")
/**
 * `HEX_OCTET_CHARACTERS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HEX_OCTET_CHARACTERS = 2
/**
 * `HEX_RADIX`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HEX_RADIX = 16
/**
 * `HTTP_CONFLICT`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HTTP_CONFLICT = 409
