package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import io.bluetape4k.workshop.commerce.voucher.fixture.FixtureRiskOperation
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherFixtureResetResult
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherFixtureResetService
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioDefinition
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioFixtures
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.reconciliation.DelayedVoucherEvent
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

/**
 * loopback demo와 integration-test profile에서만 사용하는 server-owned deterministic signal입니다.
 * @property scenarios 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property resetter 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reconciliation 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
@Profile("local", "demo", "test")
internal class VoucherScenarioFixture(
    /**
     * `scenarios`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val scenarios: VoucherScenarioFixtures,
    /**
     * `resetter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val resetter: VoucherFixtureResetService,
    /**
     * `reconciliation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reconciliation: VoucherReconciliationService,
) {
    companion object: KLogging()

    /**
     * configureAllocationReview은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    fun configureAllocationReview(
        tenantId: String,
        principalRef: String,
    ) {
        scenarios.configure("allocation-review", tenantId, principalRef)
        log.info { "voucher_fixture_configured scenario=ALLOCATION_REVIEW" }
    }

    /**
     * configure은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scenario 업무 처리에 필요한 scenario 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun configure(
        scenario: String,
        tenantId: String,
        principalRef: String,
        campaignId: UUID?,
    ): VoucherFixtureExecution {
        val definition = scenarios.configure(scenario, tenantId, principalRef)

        val evidence = if (scenario == "delayed-duplicate-out-of-order") {
            val aggregateId =
                campaignId ?: throw IllegalArgumentException("campaignId is required for delayed events")
            val eventId = UUID.nameUUIDFromBytes("$tenantId\u0000$aggregateId\u0000delayed".toUtf8Bytes())
            val applied = DelayedVoucherEvent(tenantId, eventId, "CAMPAIGN", aggregateId, "a".repeat(64), 1)
            listOf(
                reconciliation.accept(applied).outcome.name,
                reconciliation.accept(applied).outcome.name,
                reconciliation.accept(
                    DelayedVoucherEvent(
                        tenantId,
                        UUID.nameUUIDFromBytes("$tenantId\u0000$aggregateId\u0000stale".toUtf8Bytes()),
                        "CAMPAIGN",
                        aggregateId,
                        "b".repeat(64),
                        0,
                    ),
                ).outcome.name,
            )
        } else {
            emptyList()
        }
        log.info { "voucher_fixture_configured scenario=${definition.slug}" }

        return VoucherFixtureExecution(definition, evidence)
    }

    /**
     * signalFor은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun signalFor(
        tenantId: String,
        principalRef: String,
        operation: FixtureRiskOperation,
    ): RiskSignal? =
        scenarios.consumeRiskSignal(
            tenantId = tenantId,
            principalRef = principalRef,
            operation = operation
        )

    /**
     * definition은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scenario 업무 처리에 필요한 scenario 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun definition(scenario: String): VoucherScenarioDefinition =
        scenarios.definition(scenario)

    /**
     * reset은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    fun reset(tenantId: String): VoucherFixtureResetResult =
        resetter.reset(tenantId)

}
/**
 * VoucherFixtureExecution은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property definition 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property evidence 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */


internal data class VoucherFixtureExecution(
    /**
     * `definition`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val definition: VoucherScenarioDefinition,
    /**
     * `evidence`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val evidence: List<String>,
)
/**
 * VoucherFixtureRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property principalRef 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class VoucherFixtureRequest(
    @field:NotBlank @field:Size(max = 64) val principalRef: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID? = null,
)
/**
 * VoucherFixtureResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property scenario 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property principalRef 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 * @property executionMode 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property preparedNextOperation 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property evidence 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property expected 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class VoucherFixtureResponse(
    /**
     * `scenario`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scenario: String,
    /**
     * `principalRef`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principalRef: String,
    /**
     * `executionMode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val executionMode: String,
    /**
     * `preparedNextOperation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val preparedNextOperation: String?,
    /**
     * `evidence`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val evidence: List<String>,
    /**
     * `expected`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expected: io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioExpectation,
)
/**
 * VoucherFixtureResetResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property clearedSignals 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property deletedRows 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */


internal data class VoucherFixtureResetResponse(val clearedSignals: Int, val deletedRows: Int)

/**
 * guarded fixture API입니다. production profile에는 controller 전체가 없습니다.
 * @property fixture 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@RestController
@Profile("local", "demo", "test")
@RequestMapping("/operator/api/v1/fixtures")
internal class VoucherFixtureController(
    /**
     * `fixture`은 바우처·프로모션에서 결정적인 테스트 입력과 외부 경계 대역을 제공하는 fixture입니다.
     *
     * 실제 업무 결과를 대신하지 않으며 시나리오가 검증하려는 불변조건을 드러내도록 최소 상태를 구성합니다.
     */
    private val fixture: VoucherScenarioFixture,
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherHttpCommandExecutor,
) {

    private companion object: KLogging() {
        /**
         * `FIXTURE_PRINCIPAL`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        const val FIXTURE_PRINCIPAL = "workshop-fixture"
        /**
         * `FIXTURE_EVIDENCE_DESCRIPTOR_HEADER`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        const val FIXTURE_EVIDENCE_DESCRIPTOR_HEADER = "X-Workshop-Fixture-Evidence"
        /**
         * `FIXTURE_RESET_DESCRIPTOR_HEADER`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        const val FIXTURE_RESET_DESCRIPTOR_HEADER = "X-Workshop-Fixture-Reset-Result"
    }

    /**
     * `run`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = run(scenario = "demo", tenantHeader = "tenant-a", idempotencyHeader =
     * "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param scenario 요청 경계에서 검증된 `scenario`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/{scenario}/run")
    fun run(
        @PathVariable scenario: String,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: VoucherFixtureRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = requireAsciiIdentifier(body.principalRef, "principalRef")
        /**
         * `resourceId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val resourceId = UUID.nameUUIDFromBytes(
            "$tenant\u0000$principal\u0000$scenario\u0000${body.campaignId ?: "-"}".toUtf8Bytes(),
        )
        /**
         * `configured`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        var configured: VoucherFixtureExecution? = null
        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = FIXTURE_PRINCIPAL,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "FIXTURE_ALLOCATION_REVIEW",
            resourceId = resourceId,
            fingerprintMaterial = principal,
        ) {
            /**
             * `execution`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val execution = fixture.configure(scenario, tenant, principal, body.campaignId)
            configured = execution
            StoredHttpResponse(
                responseKind = VoucherResponseKind.FIXTURE_CONFIGURED,
                status = 200,
                headers = mapOf(FIXTURE_EVIDENCE_DESCRIPTOR_HEADER to execution.evidence.joinToString(",")),
                aggregateId = resourceId,
                allocationId = null,
                aggregateRevision = 0,
                generationKeyVersion = null,
                verificationKeyVersion = null,
            )
        }
        return executedResponse(executed, request) {
            /**
             * `definition`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val definition = configured?.definition ?: fixture.definition(scenario)
            /**
             * `evidence`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val evidence = executed.response.headers[FIXTURE_EVIDENCE_DESCRIPTOR_HEADER]
                .orEmpty()
                .split(',')
                .filter(String::isNotBlank)

            VoucherFixtureResponse(
                scenario = scenario,
                principalRef = principal,
                executionMode = when {
                    scenario == "delayed-duplicate-out-of-order" -> "SERVER_EVENT"
                    definition.riskOperation != null -> "SERVER_SIGNAL"
                    else                             -> "CLIENT_CHOREOGRAPHY"
                },
                preparedNextOperation = definition.riskOperation?.name,
                evidence = evidence,
                expected = definition.expected,
            )
        }
    }

    /**
     * `reset`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = reset(tenantHeader = "tenant-a", idempotencyHeader = "demo-idempotency-1",
     * request =
     * httpRequest)
     * ```
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/reset")
    fun reset(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `resourceId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val resourceId = UUID.nameUUIDFromBytes("$tenant\u0000fixture-reset".toUtf8Bytes())
        /**
         * `resetResult`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        var resetResult: VoucherFixtureResetResult? = null

        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = FIXTURE_PRINCIPAL,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "FIXTURE_RESET",
            resourceId = resourceId,
            fingerprintMaterial = tenant,
        ) {
            resetResult = fixture.reset(tenant)
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = resetResult.requireNotNull("resetResult")

            StoredHttpResponse(
                responseKind = VoucherResponseKind.FIXTURE_CONFIGURED,
                status = 200,
                headers = mapOf(FIXTURE_RESET_DESCRIPTOR_HEADER to "${result.clearedSignals}:${result.deletedRows}"),
                aggregateId = resourceId,
                allocationId = null,
                aggregateRevision = 0,
                generationKeyVersion = null,
                verificationKeyVersion = null,
            )
        }
        return executedResponse(executed, request) {
            /**
             * `values`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val values = executed.response.headers[FIXTURE_RESET_DESCRIPTOR_HEADER]
                .requireNotNull("header[$FIXTURE_RESET_DESCRIPTOR_HEADER")
                .split(':')

            VoucherFixtureResetResponse(
                clearedSignals = values[0].toInt(),
                deletedRows = values[1].toInt()
            )
        }
    }
}
