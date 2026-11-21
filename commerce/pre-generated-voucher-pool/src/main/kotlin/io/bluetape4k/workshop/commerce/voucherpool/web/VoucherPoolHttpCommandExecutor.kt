@file:Suppress("LongParameterList", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.AllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.MutationResult
import io.bluetape4k.workshop.commerce.voucherpool.application.RedeemVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.RedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReleaseAllocationCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ReplaceLostRevealCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReserveVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.RevealVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolLifecycleException
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCatalog
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.query.AllocationReadModel
import io.bluetape4k.workshop.commerce.voucherpool.query.ReservationReadModel
import io.bluetape4k.workshop.commerce.voucherpool.query.VoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.net.URI
import java.util.*

/**
 * customer HTTP precondition과 safe replay descriptor를 authoritative application service에 매핑합니다.
 * @property reservations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property allocations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property redemptions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property queries 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property admission 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */
@Component
internal class VoucherPoolHttpCommandExecutor(
    /**
     * `reservations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reservations: ReservationService,
    /**
     * `allocations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val allocations: AllocationService,
    /**
     * `redemptions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val redemptions: RedemptionService,
    /**
     * `queries`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val queries: VoucherPoolQueryService,
    /**
     * `admission`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val admission: VoucherPoolAdmissionGate,
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
) {

    private companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val STRONG_ETAG = Regex("\"(0|[1-9][0-9]*)\"")
    }

    /**
     * reserve은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ifNoneMatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun reserve(
        principal: TenantPrincipal,
        campaignId: UUID,
        idempotencyKey: String,
        ifNoneMatch: String,
        @Suppress("UNUSED_PARAMETER") request: ReserveVoucherRequest,
        requestId: String,
    ): ResponseEntity<ReservationResponse> = translate {
        requireCreatePrecondition(ifNoneMatch)
        admit(AdmissionNamespace.RESERVE, principal, campaignId)
        val result = reservations.reserve(
            ReserveVoucherCommand(
                tenantId = principal.tenantId,
                campaignId = campaignId,
                canonicalUser = principal.principalId,
                idempotencyKey = idempotencyKey,
            ),
        )
        reservationResult(principal, result, requestId, created = true)
    }

    /**
     * reservation은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param reservationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun reservation(
        principal: TenantPrincipal,
        reservationId: UUID,
        requestId: String,
    ): ResponseEntity<ReservationResponse> = translate {
        val snapshot = queries.reservation(principal.tenantId, principal.principalId, reservationId)
            ?: throw resourceNotFound()

        ResponseEntity.ok()
            .eTag(etag(snapshot.revision))
            .body(snapshot.response(requestId))
    }

    /**
     * allocate은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param reservationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun allocate(
        principal: TenantPrincipal,
        reservationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        requestId: String,
    ): ResponseEntity<AllocationResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val reservation = queries.reservation(principal.tenantId, principal.principalId, reservationId)
            ?: throw resourceNotFound()
        admit(AdmissionNamespace.ALLOCATE, principal, reservation.campaignId)
        val result = allocations.allocate(
            AllocateVoucherCommand(
                tenantId = principal.tenantId,
                campaignId = reservation.campaignId,
                reservationId = reservationId,
                canonicalUser = principal.principalId,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        allocationResult(principal, result, requestId)
    }

    /**
     * reveal은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun reveal(
        principal: TenantPrincipal,
        allocationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        requestId: String,
    ): ResponseEntity<RevealResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val allocation = ownedAllocation(principal, allocationId)
        admit(AdmissionNamespace.REVEAL, principal, allocation.campaignId)
        val result = allocations.reveal(
            RevealVoucherCommand(
                tenantId = principal.tenantId,
                campaignId = allocation.campaignId,
                allocationId = allocationId,
                canonicalUser = principal.principalId,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        revealResult(principal, allocationId, result, requestId)
    }

    /**
     * allocation은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun allocation(
        principal: TenantPrincipal,
        allocationId: UUID,
        requestId: String,
    ): ResponseEntity<AllocationResponse> = translate {
        val snapshot = ownedAllocation(principal, allocationId)
        ResponseEntity.ok()
            .eTag(etag(snapshot.revision))
            .body(snapshot.response(requestId))
    }

    /**
     * replaceLostReveal은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun replaceLostReveal(
        principal: TenantPrincipal,
        allocationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        @Suppress("UNUSED_PARAMETER") request: ReplaceLostRevealRequest,
        requestId: String,
    ): ResponseEntity<ReservationResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val allocation = ownedAllocation(principal, allocationId)
        admit(AdmissionNamespace.ALLOCATE, principal, allocation.campaignId)
        val result = allocations.replaceLostReveal(
            ReplaceLostRevealCommand(
                tenantId = principal.tenantId,
                campaignId = allocation.campaignId,
                allocationId = allocationId,
                canonicalUser = principal.principalId,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        reservationResult(principal, result, requestId, created = true)
    }

    /**
     * redeem은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun redeem(
        principal: TenantPrincipal,
        allocationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        request: RedeemVoucherRequest,
        requestId: String,
    ): ResponseEntity<AllocationResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val allocation = ownedAllocation(principal, allocationId)
        admit(AdmissionNamespace.REDEEM, principal, allocation.campaignId)
        val code = CanonicalVoucherCode.of(request.code)
        val result = redemptions.redeem(
            RedeemVoucherCommand(
                tenantId = principal.tenantId,
                campaignId = allocation.campaignId,
                allocationId = allocationId,
                canonicalUser = principal.principalId,
                code = code,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        allocationResult(principal, result, requestId)
    }

    /**
     * release은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun release(
        principal: TenantPrincipal,
        allocationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        requestId: String,
    ): ResponseEntity<AllocationResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val allocation = ownedAllocation(principal, allocationId)
        admit(AdmissionNamespace.ALLOCATE, principal, allocation.campaignId)
        val result = redemptions.release(
            ReleaseAllocationCommand(
                tenantId = principal.tenantId,
                campaignId = allocation.campaignId,
                allocationId = allocationId,
                canonicalUser = principal.principalId,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        allocationResult(principal, result, requestId)
    }

    /**
     * reservationResult은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param created 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun reservationResult(
        principal: TenantPrincipal,
        result: MutationResult<io.bluetape4k.workshop.commerce.voucherpool.application.ReservationSnapshot>,
        requestId: String,
        created: Boolean,
    ): ResponseEntity<ReservationResponse> {
        val outcome = result.commandOutcome()
        val descriptor = outcome.descriptor
        val reservationId = outcome.value?.reservationId ?: checkNotNull(descriptor?.effectId)
        val read = queries.reservation(principal.tenantId, principal.principalId, reservationId)
            ?: throw resourceNotFound()
        val response = read.response(
            requestId = requestId,
            state = descriptor?.outcome?.removePrefix("RESERVATION_") ?: read.state.name,
            revision = descriptor?.revision ?: read.revision,
        )
        val builder: ResponseEntity.BodyBuilder = if (created && !outcome.replayed) {
            ResponseEntity.created(URI.create("/api/v1/reservations/$reservationId"))
        } else {
            ResponseEntity.status(HttpStatus.OK)
        }

        return builder
            .eTag(etag(response.revision))
            .commandHeaders(outcome.replayed)
            .body(response)
    }

    /**
     * allocationResult은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun allocationResult(
        principal: TenantPrincipal,
        result: MutationResult<io.bluetape4k.workshop.commerce.voucherpool.application.AllocationSnapshot>,
        requestId: String,
    ): ResponseEntity<AllocationResponse> {
        val outcome = result.commandOutcome()
        val descriptor = outcome.descriptor
        val allocationId = outcome.value?.allocationId ?: checkNotNull(descriptor?.effectId)
        val read = queries.allocation(principal.tenantId, principal.principalId, allocationId)
            ?: throw resourceNotFound()
        val response = read.response(
            requestId = requestId,
            state = descriptor?.outcome?.removePrefix("ALLOCATION_") ?: read.state.name,
            revision = descriptor?.revision ?: read.revision,
        )
        return ResponseEntity.status(HttpStatus.OK)
            .eTag(etag(response.revision))
            .commandHeaders(outcome.replayed)
            .body(response)
    }

    /**
     * revealResult은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun revealResult(
        principal: TenantPrincipal,
        allocationId: UUID,
        result: MutationResult<io.bluetape4k.workshop.commerce.voucherpool.application.RevealResult>,
        requestId: String,
    ): ResponseEntity<RevealResponse> {
        val outcome = result.commandOutcome()
        val read = ownedAllocation(principal, allocationId)
        val reveal = outcome.value
        val descriptor = outcome.descriptor
        val stableOutcome = reveal?.outcome ?: VoucherPoolErrorCode.ALREADY_REVEALED.name
        val code = reveal?.code?.withRawValue { it }
        val replacementAvailable = code == null &&
                stableOutcome == VoucherPoolErrorCode.ALREADY_REVEALED.name &&
                read.replacementOrdinal == 0 &&
                (queries.campaign(principal.tenantId, read.campaignId)?.replacementAllowance ?: 0) >= 1

        val response = RevealResponse(
            allocationId = allocationId,
            outcome = stableOutcome,
            revision = reveal?.revision ?: checkNotNull(descriptor?.revision),
            codeAvailable = code != null,
            code = code,
            observedAt = read.observedAt,
            requestId = requestId,
            safeRequestId = requestId,
            replacementAvailable = replacementAvailable,
            nextAction = when {
                code != null -> "COPY_ONCE_OR_REDEEM"
                replacementAvailable -> "CONFIRM_REPLACEMENT_OR_REFRESH"
                else         -> "CONTACT_OPERATOR_WITH_REQUEST_ID"
            },
        )
        val duplicate = outcome.replayed || stableOutcome == VoucherPoolErrorCode.ALREADY_REVEALED.name

        return ResponseEntity.status(HttpStatus.OK)
            .eTag(etag(response.revision))
            .header("Pragma", "no-cache")
            .commandHeaders(duplicate)
            .body(response)
    }

    /**
     * ownedAllocation은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun ownedAllocation(principal: TenantPrincipal, allocationId: UUID): AllocationReadModel =
        queries.allocation(principal.tenantId, principal.principalId, allocationId) ?: throw resourceNotFound()

    /**
     * admit은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun admit(namespace: AdmissionNamespace, principal: TenantPrincipal, campaignId: UUID) {
        val principalDigest = digests.userIdentity(principal.tenantId, campaignId, principal.principalId).copyBytes()
        val decision = try {
            admission.admit(namespace, principalDigest)
        } finally {
            principalDigest.fill(0)
        }
        when (decision) {
            AdmissionDecision.ALLOW,
            AdmissionDecision.DEGRADED_ALLOW,
                                           -> Unit
            AdmissionDecision.RATE_LIMITED -> throw apiFailure(VoucherPoolErrorCode.RATE_LIMITED)
            AdmissionDecision.DATABASE_BUSY -> throw apiFailure(VoucherPoolErrorCode.POOL_BUSY)
        }
    }

    /**
     * requireCreatePrecondition은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분
     * 저장을 막습니다.
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
        val match = STRONG_ETAG.matchEntire(ifMatch) ?: throw invalidRequest()
        return match.groupValues[1].toLongOrNull() ?: throw invalidRequest()
    }

    /**
     * commandOutcome은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun <T> MutationResult<T>.commandOutcome(): CommandOutcome<T> = when (this) {
        is MutationResult.Applied -> CommandOutcome(value, null, false)
        is MutationResult.Replay -> {
            descriptor.terminalCode?.let { throw apiFailure(it) }
            CommandOutcome(null, descriptor, true)
        }
        is MutationResult.Expired -> throw apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED, effectId)
    }

    // caller validation failure는 안전한 HTTP vocabulary로 의도적으로 normalize합니다.
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
        } catch (failure: VoucherPoolLifecycleException) {
            throw apiFailure(failure.code)
        } catch (_: VoucherPoolJdbcTimeoutException) {
            throw apiFailure(VoucherPoolErrorCode.BACKEND_TIMEOUT)
        } catch (_: IllegalArgumentException) {
            throw invalidRequest()
        }

    /**
     * CommandOutcome은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    private class CommandOutcome<T>(
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val value: T?,
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val descriptor: SafeResponseDescriptor?,
        /**
         * 프로모션 바우처 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
         */
        val replayed: Boolean,
    )
}

/**
 * apiFailure은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param effectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
internal fun apiFailure(code: VoucherPoolErrorCode, effectId: UUID? = null): VoucherPoolApiException {
    if (code == VoucherPoolErrorCode.WRONG_OWNER || code == VoucherPoolErrorCode.SCOPE_NOT_FOUND) {
        return resourceNotFound()
    }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val semantics = VoucherPoolErrorCatalog[code]

    return VoucherPoolApiException(
        stableCode = code.name,
        status = semantics.httpStatus,
        safeReason = code.safeReason(),
        retryAfterSeconds = if (semantics.retryable) 1L else null,
        effectId = effectId,
    )
}

/**
 * safeReason은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun VoucherPoolErrorCode.safeReason(): String = SAFE_REASON_BY_CODE.getValue(this)

/**
 * `SAFE_REASON_BY_CODE`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private val SAFE_REASON_BY_CODE = mapOf(
    VoucherPoolErrorCode.COMMAND_IN_PROGRESS to "command is still in progress",
    VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT to "idempotency key conflicts with another request",
    VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED to "the replay window has expired",
    VoucherPoolErrorCode.POOL_BUSY to "voucher pool is temporarily unavailable",
    VoucherPoolErrorCode.BACKEND_TIMEOUT to "voucher pool is temporarily unavailable",
    VoucherPoolErrorCode.POOL_EXHAUSTED to "voucher pool is exhausted",
    VoucherPoolErrorCode.USER_LIMIT_REACHED to "voucher limit has been reached",
    VoucherPoolErrorCode.STALE_REVISION to "resource revision is stale",
    VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE to "campaign is not available",
    VoucherPoolErrorCode.CAMPAIGN_PAUSED to "campaign is not available",
    VoucherPoolErrorCode.CAMPAIGN_REVOKING to "campaign is not available",
    VoucherPoolErrorCode.CAMPAIGN_REVOKED to "campaign is not available",
    VoucherPoolErrorCode.BATCH_PAUSED to "voucher batch is not available",
    VoucherPoolErrorCode.BATCH_EXPIRING to "voucher batch is not available",
    VoucherPoolErrorCode.BATCH_REVOKED to "voucher batch is not available",
    VoucherPoolErrorCode.BATCH_EXPIRED to "voucher batch is not available",
    VoucherPoolErrorCode.BATCH_FAILED_RETRYABLE to "voucher batch is not available",
    VoucherPoolErrorCode.BATCH_FAILED_TERMINAL to "voucher batch is not available",
    VoucherPoolErrorCode.RESERVATION_EXPIRED to "reservation has expired",
    VoucherPoolErrorCode.ALLOCATION_EXPIRED to "allocation has expired",
    VoucherPoolErrorCode.RATE_LIMITED to "request rate is limited",
    VoucherPoolErrorCode.KEY_MATERIAL_UNAVAILABLE to "voucher key material is unavailable",
    VoucherPoolErrorCode.CIPHERTEXT_INVALID to "voucher key material is unavailable",
    VoucherPoolErrorCode.ALREADY_REVEALED to "voucher code was already revealed",
    VoucherPoolErrorCode.WRONG_OWNER to "resource was not found",
    VoucherPoolErrorCode.SCOPE_NOT_FOUND to "resource was not found",
).also { reasons ->
    check(reasons.keys == VoucherPoolErrorCode.entries.toSet()) {
        "Customer HTTP safe reasons must cover every voucher pool error code."
    }
}

/**
 * response은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun ReservationReadModel.response(
    requestId: String,
    state: String = this.state.name,
    revision: Long = this.revision,
): ReservationResponse = ReservationResponse(
    reservationId = reservationId,
    campaignId = campaignId,
    batchId = batchId,
    state = state,
    expiresAt = expiresAt,
    entitlementRootId = entitlementRootId,
    replacementOrdinal = replacementOrdinal,
    policyVersion = policyVersion,
    revision = revision,
    observedAt = observedAt,
    requestId = requestId,
    nextAction = state.reservationNextAction(),
)

/**
 * response은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun AllocationReadModel.response(
    requestId: String,
    state: String = this.state.name,
    revision: Long = this.revision,
): AllocationResponse = AllocationResponse(
    allocationId = allocationId,
    reservationId = reservationId,
    campaignId = campaignId,
    batchId = batchId,
    state = state,
    expiresAt = expiresAt,
    entitlementRootId = entitlementRootId,
    replacementOrdinal = replacementOrdinal,
    policyVersion = policyVersion,
    revision = revision,
    observedAt = observedAt,
    requestId = requestId,
    nextAction = state.allocationNextAction(),
)

/**
 * reservationNextAction은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun String.reservationNextAction(): String = when (this) {
    "ACTIVE" -> "ALLOCATE_OR_RELEASE"
    "ALLOCATED" -> "VIEW_ALLOCATION"
    else     -> "CREATE_RESERVATION"
}

/**
 * allocationNextAction은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun String.allocationNextAction(): String = when (this) {
    "ALLOCATED" -> "REVEAL_REDEEM_OR_RELEASE"
    "REDEEMED" -> "COMPLETE"
    else       -> "CREATE_RESERVATION"
}

/**
 * etag은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun etag(revision: Long): String = "\"$revision\""

/**
 * commandHeaders은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun ResponseEntity.BodyBuilder.commandHeaders(replayed: Boolean): ResponseEntity.BodyBuilder =
    header("Idempotency-Replay-Window", "86400")
        .apply {
            if (replayed) header("Duplicate-Request", "true")
        }
