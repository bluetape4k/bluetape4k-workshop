@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMetrics
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolSseProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolStreamShutdown
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolDepthTable
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.OutputStream
import java.io.Serializable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.toKotlinDuration
/**
 * VoucherPoolEventCursor은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class VoucherPoolEventCursor(
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `id`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
): Serializable {
    init {
        require(revision >= 0 && id >= 0)
    }

    /**
     * toString은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String = "$revision:$id"

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
        /**
         * `MAX_CURSOR_LENGTH`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val MAX_CURSOR_LENGTH = 64

        /**
         * parse은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
         * 분류합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param raw 업무 처리에 필요한 raw 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun parse(raw: String?): VoucherPoolEventCursor? {
            if (raw == null) return null
            val values = raw.takeIf {
                it.length in 3..MAX_CURSOR_LENGTH && it.all { character -> character == ':' || character.isDigit() }
            }?.split(':')?.takeIf { it.size == 2 }
            val revision = values?.get(0)?.toLongOrNull()
            val id = values?.get(1)?.toLongOrNull()
            if (revision == null || id == null) throw invalidEventCursor()
            return VoucherPoolEventCursor(revision, id)
        }
    }
}
/**
 * VoucherPoolStreamResource은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property resourceType 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property nextAction 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class VoucherPoolStreamResource(
    /**
     * `resourceType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val resourceType: String,
    /**
     * `resourceId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: UUID,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID?,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant?,
    /**
     * `nextAction`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val nextAction: String,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * VoucherPoolSnapshotResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property scope 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property reservations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property allocations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property campaigns 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property batches 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property counts 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property truncated 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property observedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property requestId 요청 추적 식별자입니다. API 오류·로그·이벤트를 하나의 업무 시도로 연결하되 비즈니스 식별자와 혼동하지 않습니다.
 */


internal data class VoucherPoolSnapshotResponse(
    /**
     * `scope`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scope: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID?,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID?,
    /**
     * `reservations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val reservations: List<VoucherPoolStreamResource>,
    /**
     * `allocations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val allocations: List<VoucherPoolStreamResource>,
    /**
     * `campaigns`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val campaigns: List<VoucherPoolStreamResource>,
    /**
     * `batches`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val batches: List<VoucherPoolStreamResource>,
    /**
     * `counts`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val counts: Map<String, Long>,
    /**
     * `truncated`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val truncated: Boolean,
    /**
     * `observedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val observedAt: Instant,
    /**
     * `requestId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val requestId: String? = null,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * VoucherPoolAuditHttpEvent은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property aggregateType 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property aggregateId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property reasonCode 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property occurredAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class VoucherPoolAuditHttpEvent(
    /**
     * `aggregateType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val aggregateType: String,
    /**
     * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: UUID,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `reasonCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonCode: String,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `occurredAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * VoucherPoolStreamScope은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface VoucherPoolStreamScope: Serializable {
    /**
     * 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId: String
}
/**
 * CustomerStreamScope은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property principalId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class CustomerStreamScope(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    override val tenantId: String,
    /**
     * `principalId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val principalId: String,
): VoucherPoolStreamScope {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * OperatorStreamScope은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class OperatorStreamScope(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    override val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID?,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID?,
): VoucherPoolStreamScope {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * VoucherPoolStreamInitial은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property snapshot 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property cursor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property resetRequired 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 * @property scanAfterId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class VoucherPoolStreamInitial(
    /**
     * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val snapshot: VoucherPoolSnapshotResponse,
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: VoucherPoolEventCursor,
    /**
     * `resetRequired`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val resetRequired: Boolean,
    /**
     * `scanAfterId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val scanAfterId: Long = cursor.id,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * VoucherPoolStreamBatch은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property snapshot 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property events 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property scannedThroughId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class VoucherPoolStreamBatch(
    /**
     * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val snapshot: VoucherPoolSnapshotResponse,
    /**
     * `events`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val events: List<VoucherPoolAuditEnvelope>,
    /**
     * `scannedThroughId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val scannedThroughId: Long = events.lastOrNull()?.cursor?.id ?: 0L,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * VoucherPoolAuditEnvelope은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property cursor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property event 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */


internal data class VoucherPoolAuditEnvelope(
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: VoucherPoolEventCursor,
    /**
     * `event`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val event: VoucherPoolAuditHttpEvent,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * CustomerAuditScan은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property events 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property scannedThroughId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


private data class CustomerAuditScan(
    /**
     * `events`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val events: List<VoucherPoolAuditEnvelope>,
    /**
     * `scannedThroughId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val scannedThroughId: Long,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * VoucherPoolEventSource은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface VoucherPoolEventSource {
    /**
     * snapshot은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse
    /**
     * initial은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun initial(scope: VoucherPoolStreamScope, cursor: VoucherPoolEventCursor?): VoucherPoolStreamInitial
    /**
     * poll은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param afterId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch
}

/**
 * 예약된 SSE lane만 사용하는 PostgreSQL-authoritative, code-free snapshot 및 audit reader입니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property mapper 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Suppress("SqlResolve")
@Component
internal class PostgresVoucherPoolEventSource(
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherPoolJdbcExecutor,
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
    properties: VoucherPoolProperties,
): VoucherPoolEventSource {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val config = properties.sse

    /**
     * snapshot은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse =
        executor.sseTransaction { snapshotInTransaction(scope) }

    /**
     * initial은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun initial(
        scope: VoucherPoolStreamScope,
        cursor: VoucherPoolEventCursor?,
    ): VoucherPoolStreamInitial =
        executor.sseTransaction {
            val snapshot = snapshotInTransaction(scope)
            val first = auditBoundary(scope, first = true)
            val last = auditBoundary(scope, first = false)
            val reset = validateCursor(scope, snapshot, cursor, first, last)
            val visibleCursor = last?.cursor ?: snapshot.fallbackCursor()
            val scanAfterId = cursor?.id ?: tenantAuditHighWatermark(scope.tenantId)

            VoucherPoolStreamInitial(snapshot, visibleCursor, reset, scanAfterId)
        }

    /**
     * poll은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param afterId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch =
        executor.sseTransaction {
            val snapshot = snapshotInTransaction(scope)
            when (scope) {
                is CustomerStreamScope -> withCustomerDigests(scope) { customerScopes ->
                    val scan = customerAuditScan(scope.tenantId, customerScopes, afterId)
                    VoucherPoolStreamBatch(snapshot, scan.events, scan.scannedThroughId)
                }
                is OperatorStreamScope -> {
                    val events = boundedAudits(scope, afterId)
                    VoucherPoolStreamBatch(snapshot, events, events.lastOrNull()?.cursor?.id ?: afterId)
                }
            }
        }

    /**
     * snapshotInTransaction은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun snapshotInTransaction(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse =
        when (scope) {
            is CustomerStreamScope -> customerSnapshot(scope)
            is OperatorStreamScope -> operatorSnapshot(scope)
        }

    /**
     * customerSnapshot은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun customerSnapshot(scope: CustomerStreamScope): VoucherPoolSnapshotResponse =
        withCustomerDigests(scope) { customerScopes ->
            val resources = readCustomerResources(scope.tenantId, customerScopes)
            val visible = resources.take(config.maxPollRows)
            VoucherPoolSnapshotResponse(
                scope = "CUSTOMER",
                campaignId = null,
                batchId = null,
                reservations = visible.filter { it.resourceType == "RESERVATION" },
                allocations = visible.filter { it.resourceType == "ALLOCATION" },
                campaigns = emptyList(),
                batches = emptyList(),
                counts = visible.groupingBy(VoucherPoolStreamResource::state).eachCount()
                    .mapValues { (_, count) -> count.toLong() },
                truncated = resources.size > config.maxPollRows,
                observedAt = currentConnection().observedAt(),
            )
        }

    /**
     * operatorSnapshot은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun operatorSnapshot(scope: OperatorStreamScope): VoucherPoolSnapshotResponse {
        val resolved = resolveOperatorScope(scope)
        val campaigns = readOperatorCampaigns(scope.tenantId, resolved.campaignId)
        val batches = readOperatorBatches(scope.tenantId, resolved.campaignId, resolved.batchId)
        val counts = readOperatorCounts(scope.tenantId, resolved.campaignId, resolved.batchId)

        return VoucherPoolSnapshotResponse(
            scope = "OPERATOR",
            campaignId = resolved.campaignId,
            batchId = resolved.batchId,
            reservations = emptyList(),
            allocations = emptyList(),
            campaigns = campaigns,
            batches = batches,
            counts = counts,
            truncated = campaigns.size >= config.maxPollRows || batches.size >= config.maxPollRows,
            observedAt = currentConnection().observedAt(),
        )
    }

    /**
     * readCustomerResources은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param scopes 업무 처리에 필요한 scopes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun readCustomerResources(
        tenantId: String,
        scopes: List<CustomerCampaignDigest>,
    ): List<VoucherPoolStreamResource> {
        if (scopes.isEmpty()) return emptyList()
        // 고객 reservation/allocation을 하나의 bounded UNION으로 합치고 digest scope를 bytea로 바인딩해야 합니다.
        val sql = """
            WITH owner_scope(campaign_id,user_digest) AS (${customerScopeValues(scopes)})
                SELECT resource_type,resource_id,campaign_id,batch_id,state,revision,expires_at
                FROM (
                    SELECT 'RESERVATION' AS resource_type,r.reservation_id AS resource_id,r.campaign_id,r.batch_id,
                           r.state,r.revision,r.reservation_expires_at AS expires_at
                    FROM voucher_pool_reservations r
                    JOIN owner_scope s ON s.campaign_id=r.campaign_id AND s.user_digest=r.user_digest
                    WHERE r.tenant_id=?
                    UNION ALL
                    SELECT 'ALLOCATION',a.allocation_id,a.campaign_id,a.batch_id,e.state,a.revision,a.allocation_expires_at
                    FROM voucher_pool_allocations a
                    JOIN owner_scope s ON s.campaign_id=a.campaign_id AND s.user_digest=a.user_digest
                    JOIN voucher_pool_entries e ON e.tenant_id=a.tenant_id AND e.entry_id=a.entry_id
                    WHERE a.tenant_id=?
                ) owned
                ORDER BY expires_at,resource_id LIMIT ?""".trimIndent()

        return currentConnection().prepareStatement(sql).use { statement ->
            var index = statement.bindCustomerScopes(scopes)
            statement.setString(index++, tenantId)
            statement.setString(index++, tenantId)
            statement.setInt(index, config.maxPollRows + 1)

            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val state = result.getString("state")
                        val type = result.getString("resource_type")
                        add(
                            VoucherPoolStreamResource(
                                resourceType = type,
                                resourceId = result.getObject("resource_id", UUID::class.java),
                                campaignId = result.getObject("campaign_id", UUID::class.java),
                                batchId = result.getObject("batch_id", UUID::class.java),
                                state = state,
                                revision = result.getLong("revision"),
                                expiresAt = result.getTimestamp("expires_at").toInstant(),
                                nextAction = customerNextAction(type, state),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * readOperatorCampaigns은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun readOperatorCampaigns(tenantId: String, campaignId: UUID?): List<VoucherPoolStreamResource> {
        val campaignPredicate = campaignId?.let { VoucherPoolCampaignTable.campaignId eq it }
            ?: org.jetbrains.exposed.v1.core.Op.TRUE
        return VoucherPoolCampaignTable
            .selectAll()
            .where {
                (VoucherPoolCampaignTable.tenantId eq tenantId) and campaignPredicate
            }
            .orderBy(VoucherPoolCampaignTable.campaignId)
            .limit(config.maxPollRows)
            .map { row ->
                val id = row[VoucherPoolCampaignTable.campaignId]
                val state = row[VoucherPoolCampaignTable.state].name
                VoucherPoolStreamResource(
                    resourceType = "CAMPAIGN",
                    resourceId = id,
                    campaignId = id,
                    batchId = null,
                    state = state,
                    revision = row[VoucherPoolCampaignTable.revision],
                    expiresAt = row[VoucherPoolCampaignTable.endsAt],
                    nextAction = campaignNextAction(state),
                )
            }
    }

    /**
     * readOperatorBatches은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun readOperatorBatches(
        tenantId: String,
        campaignId: UUID?,
        batchId: UUID?,
    ): List<VoucherPoolStreamResource> {
        val campaignPredicate = campaignId?.let { VoucherPoolBatchTable.campaignId eq it }
            ?: org.jetbrains.exposed.v1.core.Op.TRUE
        val batchPredicate = batchId?.let { VoucherPoolBatchTable.batchId eq it }
            ?: org.jetbrains.exposed.v1.core.Op.TRUE
        return VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and campaignPredicate and batchPredicate
            }
            .orderBy(VoucherPoolBatchTable.batchId)
            .limit(config.maxPollRows)
            .map { row ->
                val state = row[VoucherPoolBatchTable.state].name
                val id = row[VoucherPoolBatchTable.batchId]
                VoucherPoolStreamResource(
                    resourceType = "BATCH",
                    resourceId = id,
                    campaignId = row[VoucherPoolBatchTable.campaignId],
                    batchId = id,
                    state = state,
                    revision = row[VoucherPoolBatchTable.revision],
                    expiresAt = row[VoucherPoolBatchTable.expiresAt],
                    nextAction = batchNextAction(state),
                )
            }
    }

    /**
     * readOperatorCounts은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun readOperatorCounts(tenantId: String, campaignId: UUID?, batchId: UUID?): Map<String, Long> {
        val eligibleBatchIds = campaignId?.let { id ->
            VoucherPoolBatchTable
                .select(VoucherPoolBatchTable.batchId)
                .where {
                    (VoucherPoolBatchTable.tenantId eq tenantId) and
                            (VoucherPoolBatchTable.campaignId eq id)
                }
                .map { it[VoucherPoolBatchTable.batchId] }
        }
        if (eligibleBatchIds != null && eligibleBatchIds.isEmpty()) return emptyMap()

        val depth = VoucherPoolDepthTable.entryCount.sum()
        val batchPredicate = batchId?.let { VoucherPoolDepthTable.batchId eq it }
            ?: org.jetbrains.exposed.v1.core.Op.TRUE
        val eligiblePredicate = eligibleBatchIds?.let { VoucherPoolDepthTable.batchId inList it }
            ?: org.jetbrains.exposed.v1.core.Op.TRUE
        return VoucherPoolDepthTable
            .select(VoucherPoolDepthTable.state, depth)
            .where {
                (VoucherPoolDepthTable.tenantId eq tenantId) and batchPredicate and eligiblePredicate
            }
            .groupBy(VoucherPoolDepthTable.state)
            .associate { row -> row[VoucherPoolDepthTable.state].name to (row[depth] ?: 0L) }
    }

    /**
     * resolveOperatorScope은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun resolveOperatorScope(scope: OperatorStreamScope): ResolvedOperatorScope =
        scope.batchId?.let { resolveBatchScope(scope, it) } ?: resolveCampaignScope(scope)

    /**
     * resolveBatchScope은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun resolveBatchScope(scope: OperatorStreamScope, batchId: UUID): ResolvedOperatorScope {
        val actualCampaign = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.campaignId)
            .where {
                (VoucherPoolBatchTable.tenantId eq scope.tenantId) and
                        (VoucherPoolBatchTable.batchId eq batchId)
            }
            .singleOrNull()
            ?.get(VoucherPoolBatchTable.campaignId)
            ?: throw resourceNotFound()

        if (scope.campaignId != null && scope.campaignId != actualCampaign) throw resourceNotFound()
        return ResolvedOperatorScope(actualCampaign, batchId)
    }

    /**
     * resolveCampaignScope은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun resolveCampaignScope(scope: OperatorStreamScope): ResolvedOperatorScope {
        val campaignId = scope.campaignId
        if (campaignId != null && !campaignExists(scope.tenantId, campaignId)) throw resourceNotFound()

        return ResolvedOperatorScope(campaignId, null)
    }

    /**
     * campaignExists은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun campaignExists(tenantId: String, campaignId: UUID): Boolean =
        VoucherPoolCampaignTable
            .select(VoucherPoolCampaignTable.campaignId)
            .where {
                (VoucherPoolCampaignTable.tenantId eq tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .limit(1)
            .any()

    /**
     * boundedAudits은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param afterId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun boundedAudits(scope: VoucherPoolStreamScope, afterId: Long): List<VoucherPoolAuditEnvelope> {
        val candidates = when (scope) {
            is OperatorStreamScope -> operatorAudits(scope, afterId, config.maxPollRows)
            is CustomerStreamScope -> withCustomerDigests(scope) { customerScopes ->
                customerAudits(scope.tenantId, customerScopes, afterId, config.maxPollRows)
            }
        }.sortedBy { it.cursor.id }
        val bounded = ArrayList<VoucherPoolAuditEnvelope>()
        var encodedBytes = 0

        for (candidate in candidates) {
            val size = mapper.writeValueAsBytes(candidate.event).size + candidate.cursor.toString().length + 32
            if (bounded.size >= config.maxPollRows || encodedBytes + size > config.maxPollBytes) break
            bounded += candidate
            encodedBytes += size
        }
        return bounded
    }

    /**
     * auditBoundary은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param first 업무 처리에 필요한 first 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun auditBoundary(scope: VoucherPoolStreamScope, first: Boolean): VoucherPoolAuditEnvelope? =
        when (scope) {
            is OperatorStreamScope -> operatorAudits(scope, if (first) -1 else 0, 1, descending = !first).firstOrNull()
            is CustomerStreamScope -> withCustomerDigests(scope) { customerScopes ->
                customerAudits(scope.tenantId, customerScopes, if (first) -1 else 0, 1, descending = !first)
                    .firstOrNull()
            }
        }

    /**
     * operatorAudits은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param afterId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param descending 업무 처리에 필요한 descending 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun operatorAudits(
        scope: OperatorStreamScope,
        afterId: Long,
        limit: Int,
        descending: Boolean = false,
    ): List<VoucherPoolAuditEnvelope> {
        // aggregate 종류별 batch/entry/reservation/allocation EXISTS와 방향성 cursor를 한 SQL로 유지합니다.
        val resolved = resolveOperatorScope(scope)
        val clauses = mutableListOf("a.tenant_id=?")
        if (resolved.batchId != null) {
            clauses += """a.campaign_id=? AND (
                (a.aggregate_type='BATCH' AND a.aggregate_id=?) OR
                (a.aggregate_type='CAMPAIGN' AND a.aggregate_id=a.campaign_id) OR
                (a.aggregate_type='RECONCILIATION' AND a.aggregate_id=?) OR
                (a.aggregate_type='ENTRY' AND EXISTS (SELECT 1 FROM voucher_pool_entries e
                    WHERE e.tenant_id=a.tenant_id AND e.entry_id=a.aggregate_id AND e.batch_id=?)) OR
                (a.aggregate_type='RESERVATION' AND EXISTS (SELECT 1 FROM voucher_pool_reservations r
                    WHERE r.tenant_id=a.tenant_id AND r.reservation_id=a.aggregate_id AND r.batch_id=?)) OR
                (a.aggregate_type='ALLOCATION' AND EXISTS (SELECT 1 FROM voucher_pool_allocations x
                    WHERE x.tenant_id=a.tenant_id AND x.allocation_id=a.aggregate_id AND x.batch_id=?)))""".trimIndent()
        } else if (resolved.campaignId != null) {
            clauses += "a.campaign_id=?"
        }
        if (afterId >= 0) clauses += "a.id>?"
        val order = if (descending) "DESC" else "ASC"
        val sql = auditSelect("${clauses.joinToString(" AND ")} ORDER BY a.id $order LIMIT ?")

        return currentConnection().prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, scope.tenantId)
            resolved.campaignId?.let { statement.setObject(index++, it) }
            resolved.batchId?.let { batch -> repeat(5) { statement.setObject(index++, batch) } }
            if (afterId >= 0) statement.setLong(index++, afterId)
            statement.setInt(index, limit)
            statement.executeQuery().use(::readAudits)
        }
    }

    /**
     * customerAudits은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param scopes 업무 처리에 필요한 scopes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param afterId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param descending 업무 처리에 필요한 descending 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun customerAudits(
        tenantId: String,
        scopes: List<CustomerCampaignDigest>,
        afterId: Long,
        limit: Int,
        descending: Boolean = false,
    ): List<VoucherPoolAuditEnvelope> {
        if (scopes.isEmpty()) return emptyList()
        // owner_scope CTE와 customer digest visibility를 audit cursor와 함께 평가해야 합니다.
        val after = if (afterId >= 0) "AND a.id>?" else ""
        val order = if (descending) "DESC" else "ASC"
        val sql = auditSelect(
            """a.tenant_id=? AND (
                (a.aggregate_type='RESERVATION' AND EXISTS (SELECT 1 FROM voucher_pool_reservations r
                    JOIN owner_scope s ON s.campaign_id=r.campaign_id AND s.user_digest=r.user_digest
                    WHERE r.tenant_id=a.tenant_id AND r.reservation_id=a.aggregate_id)) OR
                (a.aggregate_type='ALLOCATION' AND EXISTS (SELECT 1 FROM voucher_pool_allocations x
                    JOIN owner_scope s ON s.campaign_id=x.campaign_id AND s.user_digest=x.user_digest
                    WHERE x.tenant_id=a.tenant_id AND x.allocation_id=a.aggregate_id)))
                $after ORDER BY a.id $order LIMIT ?""".trimIndent(),
            prefix = "WITH owner_scope(campaign_id,user_digest) AS (${customerScopeValues(scopes)}) ",
        )
        return currentConnection().prepareStatement(sql).use { statement ->
            var index = statement.bindCustomerScopes(scopes)
            statement.setString(index++, tenantId)
            if (afterId >= 0) statement.setLong(index++, afterId)
            statement.setInt(index, limit)
            statement.executeQuery().use(::readAudits)
        }
    }

    /**
     * customerAuditScan은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param scopes 업무 처리에 필요한 scopes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param afterId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun customerAuditScan(
        tenantId: String,
        scopes: List<CustomerCampaignDigest>,
        afterId: Long,
    ): CustomerAuditScan {
        if (scopes.isEmpty()) return CustomerAuditScan(emptyList(), tenantAuditHighWatermark(tenantId))
        // bounded raw_audits CTE로 스캔 cursor와 visible 필터를 분리해 누락 없는 재개를 보장합니다.
        val sql = """
            WITH owner_scope(campaign_id,user_digest) AS (${customerScopeValues(scopes)}), raw_audits AS (
                    SELECT a.* FROM voucher_pool_audits a
                    WHERE a.tenant_id=? AND a.id>? ORDER BY a.id LIMIT ?
                ) SELECT a.*,
                    ((a.aggregate_type='RESERVATION' AND EXISTS (SELECT 1 FROM voucher_pool_reservations r
                        JOIN owner_scope s ON s.campaign_id=r.campaign_id AND s.user_digest=r.user_digest
                        WHERE r.tenant_id=a.tenant_id AND r.reservation_id=a.aggregate_id)) OR
                     (a.aggregate_type='ALLOCATION' AND EXISTS (SELECT 1 FROM voucher_pool_allocations x
                        JOIN owner_scope s ON s.campaign_id=x.campaign_id AND s.user_digest=x.user_digest
                        WHERE x.tenant_id=a.tenant_id AND x.allocation_id=a.aggregate_id))) AS visible
                FROM raw_audits a ORDER BY a.id
            """.trimIndent()

        return currentConnection().prepareStatement(sql).use { statement ->
            var index = statement.bindCustomerScopes(scopes)
            statement.setString(index++, tenantId)
            statement.setLong(index++, afterId)
            statement.setInt(index, config.maxPollRows)
            statement.executeQuery().use { result ->
                val events = ArrayList<VoucherPoolAuditEnvelope>()
                var scannedThroughId = afterId
                var encodedBytes = 0
                while (result.next()) {
                    val envelope = result.auditEnvelope()
                    if (result.getBoolean("visible")) {
                        val size = mapper.writeValueAsBytes(envelope.event).size +
                                envelope.cursor.toString().length + 32
                        if (events.size >= config.maxPollRows || encodedBytes + size > config.maxPollBytes) break
                        events += envelope
                        encodedBytes += size
                    }
                    scannedThroughId = envelope.cursor.id
                }
                CustomerAuditScan(events, scannedThroughId)
            }
        }
    }

    /**
     * validateCursor은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param snapshot 업무 처리에 필요한 snapshot 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param requested 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param first 업무 처리에 필요한 first 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param last 업무 처리에 필요한 last 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "ThrowsCount")
    private fun validateCursor(
        scope: VoucherPoolStreamScope,
        snapshot: VoucherPoolSnapshotResponse,
        requested: VoucherPoolEventCursor?,
        first: VoucherPoolAuditEnvelope?,
        last: VoucherPoolAuditEnvelope?,
    ): Boolean {
        if (requested == null) return false
        val maximumRevision = maxOf(snapshot.maximumRevision(), last?.cursor?.revision ?: 0)
        if (requested.revision > maximumRevision) throw invalidEventCursor()
        if (last == null) {
            if (requested.id > 0) throw invalidEventCursor()
            return false
        }
        if (requested.id > last.cursor.id) throw invalidEventCursor()
        if (requested.id == 0L) return first != null
        val referenced = findScopedAudit(scope, requested.id)
        if (referenced != null && referenced.cursor.revision != requested.revision) throw invalidEventCursor()
        if (referenced == null && auditExists(requested.id)) throw invalidEventCursor()
        if (referenced == null && (first == null || requested.id >= first.cursor.id)) throw invalidEventCursor()
        return referenced == null
    }

    /**
     * findScopedAudit은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun findScopedAudit(scope: VoucherPoolStreamScope, id: Long): VoucherPoolAuditEnvelope? =
        when (scope) {
            is OperatorStreamScope -> operatorAudits(scope, id - 1, 1).firstOrNull()?.takeIf { it.cursor.id == id }
            is CustomerStreamScope -> withCustomerDigests(scope) { customerScopes ->
                customerAudits(scope.tenantId, customerScopes, id - 1, 1).firstOrNull()
                    ?.takeIf { it.cursor.id == id }
            }
        }

    /**
     * auditExists은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun auditExists(id: Long): Boolean =
        VoucherPoolAuditTable
            .select(VoucherPoolAuditTable.id)
            .where { VoucherPoolAuditTable.id eq id }
            .limit(1)
            .any()

    /**
     * tenantAuditHighWatermark은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    private fun tenantAuditHighWatermark(tenantId: String): Long {
        val maximum = VoucherPoolAuditTable.id.max()
        return VoucherPoolAuditTable
            .select(maximum)
            .where { VoucherPoolAuditTable.tenantId eq tenantId }
            .singleOrNull()
            ?.get(maximum)
            ?: 0L
    }

    /**
     * withCustomerDigests은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> withCustomerDigests(scope: CustomerStreamScope, block: (List<CustomerCampaignDigest>) -> T): T {
        val campaigns = VoucherPoolCampaignTable
            .select(
                VoucherPoolCampaignTable.campaignId,
                VoucherPoolCampaignTable.userIdentityKeyVersion,
            )
            .where { VoucherPoolCampaignTable.tenantId eq scope.tenantId }
            .orderBy(VoucherPoolCampaignTable.campaignId)
            .limit(config.maxCustomerCampaigns + 1)
            .map {
                it[VoucherPoolCampaignTable.campaignId] to it[VoucherPoolCampaignTable.userIdentityKeyVersion]
            }
        if (campaigns.size > config.maxCustomerCampaigns) {
            throw VoucherPoolApiException(
                "SNAPSHOT_SCOPE_TOO_LARGE",
                503,
                "snapshot scope is temporarily unavailable",
                1,
            )
        }
        val values = campaigns.map { (campaignId, keyVersion) ->
            val digest = digests.userIdentity(scope.tenantId, campaignId, scope.principalId, keyVersion).copyBytes()
            CustomerCampaignDigest(campaignId, digest)
        }
        return try {
            block(values)
        } finally {
            values.forEach { it.digest.fill(0) }
        }
    }

    /**
     * readAudits은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    private fun readAudits(result: ResultSet): List<VoucherPoolAuditEnvelope> =
        buildList { while (result.next()) add(result.auditEnvelope()) }

    /**
     * auditEnvelope은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ResultSet.auditEnvelope(): VoucherPoolAuditEnvelope {
        val cursor = VoucherPoolEventCursor(getLong("revision"), getLong("id"))
        return VoucherPoolAuditEnvelope(
            cursor,
            VoucherPoolAuditHttpEvent(
                aggregateType = getString("aggregate_type"),
                aggregateId = getObject("aggregate_id", UUID::class.java),
                revision = cursor.revision,
                reasonCode = getString("reason_code"),
                policyVersion = getLong("policy_version"),
                occurredAt = getTimestamp("created_at").toInstant(),
            ),
        )
    }

    /**
     * auditSelect은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param predicateAndOrder 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param prefix 업무 처리에 필요한 prefix 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun auditSelect(predicateAndOrder: String, prefix: String = ""): String =
        """${prefix}SELECT a.id,a.aggregate_type,a.aggregate_id,a.revision,a.reason_code,a.policy_version,a.created_at
            FROM voucher_pool_audits a WHERE $predicateAndOrder""".trimIndent()

    /**
     * customerScopeValues은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scopes 업무 처리에 필요한 scopes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun customerScopeValues(scopes: List<CustomerCampaignDigest>): String =
        "VALUES " + scopes.joinToString(",") { "(?::uuid,?::bytea)" }

    /**
     * bindCustomerScopes은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scopes 업무 처리에 필요한 scopes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun PreparedStatement.bindCustomerScopes(scopes: List<CustomerCampaignDigest>): Int {
        var index = 1
        scopes.forEach { scope ->
            setObject(index++, scope.campaignId)
            setBytes(index++, scope.digest)
        }
        return index
    }

    /**
     * currentConnection은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun currentConnection(): Connection =
        TransactionManager.currentOrNull()
            ?.connection
            ?.connection as? Connection ?: error("voucher pool event source requires an active JDBC transaction")
    /**
     * CustomerCampaignDigest은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property campaignId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
     * @property digest 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */


    private class CustomerCampaignDigest(val campaignId: UUID, val digest: ByteArray)
    /**
     * ResolvedOperatorScope은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property campaignId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
     * @property batchId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
     */

    private class ResolvedOperatorScope(val campaignId: UUID?, val batchId: UUID?)
}

/**
 * 공유 bounded poller는 JDBC permit을 subscriber queue와 network write 바깥에 둡니다.
 * @property source 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class VoucherPoolEventStream(
    /**
     * `source`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val source: VoucherPoolEventSource,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: ExecutorService,
    properties: VoucherPoolProperties,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: VoucherPoolMetrics,
): AutoCloseable, VoucherPoolStreamShutdown {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val config: VoucherPoolSseProperties = properties.sse
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val registryLock = ReentrantLock()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val pollers = HashMap<VoucherPoolStreamScope, ScopePoller>()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val activeSubscribers = AtomicInteger()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val closed = AtomicBoolean()

    /**
     * customerSnapshot은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun customerSnapshot(tenantId: String, principalId: String, requestId: String): VoucherPoolSnapshotResponse =
        source.snapshot(CustomerStreamScope(tenantId, principalId)).copy(requestId = requestId)

    /**
     * operatorSnapshot은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun operatorSnapshot(
        tenantId: String,
        campaignId: UUID?,
        batchId: UUID?,
        requestId: String,
    ): VoucherPoolSnapshotResponse =
        source.snapshot(OperatorStreamScope(tenantId, campaignId, batchId)).copy(requestId = requestId)

    /**
     * openCustomer은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun openCustomer(
        tenantId: String,
        principalId: String,
        cursor: VoucherPoolEventCursor?,
    ): StreamSubscription = open(CustomerStreamScope(tenantId, principalId), cursor)

    /**
     * openOperator은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun openOperator(
        tenantId: String,
        campaignId: UUID?,
        batchId: UUID?,
        cursor: VoucherPoolEventCursor?,
    ): StreamSubscription = open(OperatorStreamScope(tenantId, campaignId, batchId), cursor)

    /**
     * write은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subscription 업무 처리에 필요한 subscription 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param output 업무 처리에 필요한 output 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun write(subscription: StreamSubscription, output: OutputStream) {
        try {
            var streaming = true
            while (streaming && !subscription.isClosed()) {
                val next = subscription.next(config.heartbeatInterval)
                streaming = next != null && !next.terminal
                if (next != null) writeWithDeadline(output, next)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: TimeoutException) {
            log.warn { "voucher_pool_sse_disconnected reason=write_timeout" }
        } catch (_: ExecutionException) {
            log.info { "voucher_pool_sse_disconnected reason=write_failure" }
        } catch (_: IOException) {
            log.info { "voucher_pool_sse_disconnected reason=client_close" }
        } finally {
            subscription.close()
        }
    }

    /**
     * activePollers은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    internal fun activePollers(): Int = registryLock.withLock { pollers.size }

    /**
     * closeSseAndPoller은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun closeSseAndPoller() = close()

    /**
     * close은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = registryLock.withLock { pollers.values.toList().also { pollers.clear() } }
        active.forEach(ScopePoller::stop)
        metrics.sseSubscribers(0)
    }

    /**
     * open은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun open(scope: VoucherPoolStreamScope, cursor: VoucherPoolEventCursor?): StreamSubscription {
        reserveSubscriber(scope)
        val initial = try {
            source.initial(scope, cursor)
        } catch (failure: RuntimeException) {
            releaseSubscriber()
            throw failure
        }
        val subscription = StreamSubscription(initial.cursor)
        subscription.offer(event(initial.cursor, "snapshot", initial.snapshot))
        if (initial.resetRequired) {
            val reset = event(initial.cursor, "reset", initial.snapshot, terminal = true)
            if (!subscription.offer(reset)) subscription.forceTerminal(reset)
            metrics.sseReset()
            return subscription
        }
        try {
            val registration = registryLock.withLock {
                if (closed.get()) throw serviceShuttingDown()
                val existing = pollers[scope]
                val poller = existing ?: ScopePoller(
                    scope,
                    initial.cursor,
                    initial.snapshot,
                    initial.scanAfterId,
                ).also {
                    pollers[scope] = it
                }
                if (poller.subscriberCount() >= config.maxSubscribersPerScope) throw sseCapacityRejected()
                poller.attach(subscription)
                PollerRegistration(poller, existing == null)
            }
            registration.poller.activate(subscription, initial.cursor)
            if (registration.created) registration.poller.start()
        } catch (failure: RuntimeException) {
            subscription.close()
            throw failure
        }
        return subscription
    }

    /**
     * reserveSubscriber은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun reserveSubscriber(scope: VoucherPoolStreamScope) {
        registryLock.withLock {
            if (closed.get()) throw serviceShuttingDown()
            val globalCapacityReached = activeSubscribers.get() >= config.maxSubscribers
            val scopeCapacityReached = (pollers[scope]?.subscriberCount() ?: 0) >= config.maxSubscribersPerScope
            if (globalCapacityReached || scopeCapacityReached) throw sseCapacityRejected()
            val count = activeSubscribers.incrementAndGet()
            metrics.sseSubscribers(count)
        }
    }

    /**
     * releaseSubscriber은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun releaseSubscriber() {
        val count = activeSubscribers.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        metrics.sseSubscribers(count)
    }

    /**
     * remove은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subscription 업무 처리에 필요한 subscription 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun remove(subscription: StreamSubscription) {
        val stopped = registryLock.withLock {
            val poller = subscription.poller.getAndSet(null) ?: return@withLock null
            poller.detach(subscription)
            poller.takeIf { it.subscriberCount() == 0 && pollers.remove(it.scope, it) }
        }
        stopped?.stop()
        releaseSubscriber()
    }

    /**
     * event은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param type 업무 처리에 필요한 type 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param payload 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param terminal 업무 처리에 필요한 terminal 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun event(
        cursor: VoucherPoolEventCursor,
        type: String,
        payload: Any,
        terminal: Boolean = false,
    ): VoucherPoolStreamEvent = VoucherPoolStreamEvent(cursor, type, mapper.writeValueAsString(payload), terminal)

    /**
     * writeWithDeadline은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param output 업무 처리에 필요한 output 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun writeWithDeadline(output: OutputStream, event: VoucherPoolStreamEvent) {
        val bytes = event.encode()
        val write = executor.submit { output.write(bytes); output.flush() }

        try {
            write.get(config.writeTimeout.toKotlinDuration())
        } catch (failure: TimeoutException) {
            write.cancel(true)
            throw failure
        }
    }

    /**
     * StreamSubscription은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    internal inner class StreamSubscription(initialCursor: VoucherPoolEventCursor): AutoCloseable {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val queue = ArrayBlockingQueue<VoucherPoolStreamEvent>(config.queueSize)
        /**
         * `queuedBytes`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private val queuedBytes = AtomicInteger()
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val cursor = AtomicReference(initialCursor)
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val closed = AtomicBoolean()
        /**
         * `cleanupCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private val cleanupCount = AtomicLong()
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val live = AtomicBoolean()
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        internal val poller = AtomicReference<ScopePoller?>()

        /**
         * offer은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param next 업무 처리에 필요한 next 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        internal fun offer(next: VoucherPoolStreamEvent): Boolean = when {
            closed.get() -> false
            next.type == "audit" && next.cursor.id <= cursor.get().id -> true
            else         -> enqueue(next)
        }

        /**
         * enqueue은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param next 업무 처리에 필요한 next 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        private fun enqueue(next: VoucherPoolStreamEvent): Boolean {
            val size = next.encodedSize()
            val withinByteLimit = size <= config.maxQueueBytes && queuedBytes.get() + size <= config.maxQueueBytes
            val accepted = withinByteLimit && queue.offer(next)
            if (accepted) {
                queuedBytes.addAndGet(size)
                cursor.set(next.cursor)
            }
            return accepted
        }

        /**
         * forceTerminal은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
         * 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param next 업무 처리에 필요한 next 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        internal fun forceTerminal(next: VoucherPoolStreamEvent) {
            queue.clear()
            queuedBytes.set(0)
            queue.offer(next)
            queuedBytes.set(next.encodedSize())
            cursor.set(next.cursor)
        }

        /**
         * overflow은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param snapshot 업무 처리에 필요한 snapshot 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        internal fun overflow(snapshot: VoucherPoolSnapshotResponse) {
            if (closed.get()) return
            forceTerminal(event(cursor.get(), "reset", snapshot, terminal = true))
            metrics.sseReset()
        }

        /**
         * terminate은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        internal fun terminate(code: String) {
            if (closed.get()) return
            forceTerminal(event(cursor.get(), "error", mapOf("code" to code), terminal = true))
        }

        /**
         * next은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         */
        internal fun next(timeout: Duration): VoucherPoolStreamEvent? {
            return if (closed.get() && queue.isEmpty()) {
                null
            } else {
                queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)?.also { value ->
                    queuedBytes.addAndGet(-value.encodedSize())
                } ?: event(cursor.get(), "heartbeat", mapOf("observedAt" to Instant.now()))
            }
        }

        /**
         * attach은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
         */
        internal fun attach(owner: ScopePoller) {
            check(poller.compareAndSet(null, owner))
        }

        /**
         * activate은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        internal fun activate() {
            live.set(true)
        }

        /**
         * isLive은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        internal fun isLive(): Boolean = live.get()

        /**
         * isClosed은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        internal fun isClosed(): Boolean = closed.get()
        /**
         * queueDepth은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
         * 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        internal fun queueDepth(): Int = queue.size
        /**
         * cleanupInvocationCount은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
         * 계약된 응답으로
         * 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        internal fun cleanupInvocationCount(): Long = cleanupCount.get()

        /**
         * close은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            cleanupCount.incrementAndGet()
            queue.clear()
            queuedBytes.set(0)
            remove(this)
        }
    }
    /**
     * ScopePoller은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property scope 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     */


    internal inner class ScopePoller(
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val scope: VoucherPoolStreamScope,
        initialCursor: VoucherPoolEventCursor,
        initialSnapshot: VoucherPoolSnapshotResponse,
        initialScanAfterId: Long = initialCursor.id,
    ) {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val subscribers = ConcurrentHashMap.newKeySet<StreamSubscription>()
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val cursor = AtomicReference(initialCursor)
        /**
         * `scanAfterId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        private val scanAfterId = AtomicLong(initialScanAfterId)
        /**
         * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
         */
        private val snapshot = AtomicReference(initialSnapshot.normalized())
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val running = AtomicBoolean(true)
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val task = AtomicReference<Future<*>?>()
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private val delay = AtomicReference(config.pollInterval)
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val cycleLock = ReentrantLock()

        /**
         * start은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        fun start() {
            task.set(executor.submit(::run))
        }

        /**
         * attach은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param subscription 업무 처리에 필요한 subscription 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun attach(subscription: StreamSubscription) {
            subscription.attach(this)
            subscribers += subscription
        }

        /**
         * detach은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param subscription 업무 처리에 필요한 subscription 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun detach(subscription: StreamSubscription) {
            subscribers -= subscription
        }

        /**
         * activate은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param subscription 업무 처리에 필요한 subscription 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param initialCursor 업무 처리에 필요한 initialCursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun activate(subscription: StreamSubscription, initialCursor: VoucherPoolEventCursor) {
            cycleLock.withLock {
                check(running.get()) { "SSE poller is stopped" }
                val target = cursor.get()
                if (target.id > initialCursor.id) {
                    val catchup = source.poll(scope, initialCursor.id)
                    if (catchup.scannedThroughId < target.id) {
                        subscription.overflow(catchup.snapshot)
                        return
                    }
                    catchup.events.forEach { event ->
                        val next = this@VoucherPoolEventStream.event(event.cursor, "audit", event.event)
                        if (!subscription.offer(next)) {
                            subscription.overflow(catchup.snapshot)
                            return
                        }
                    }
                }
                subscription.activate()
            }
        }

        /**
         * subscriberCount은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
         * 응답으로 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        fun subscriberCount(): Int = subscribers.size

        /**
         * stop은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        fun stop() {
            if (!running.compareAndSet(true, false)) return
            task.getAndSet(null)?.cancel(true)
            subscribers.toList().forEach(StreamSubscription::close)
            subscribers.clear()
        }

        /**
         * run은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun run() {
            while (running.get() && sleep(delay.get())) {
                try {
                    cycleLock.withLock {
                        if (!running.get()) return
                        val batch = source.poll(scope, scanAfterId.get())
                        process(batch)
                    }
                } catch (failure: VoucherPoolApiException) {
                    subscribers.toList().forEach { it.terminate(failure.stableCode) }
                    stop()
                } catch (failure: RuntimeException) {
                    if (running.get()) {
                        log.warn { "voucher_pool_sse_poll_failed failure=${failure.javaClass.simpleName}" }
                    }
                    delay.set(config.maxIdleInterval)
                }
            }
        }

        /**
         * process은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param batch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         */
        private fun process(batch: VoucherPoolStreamBatch) {
            scanAfterId.updateAndGet { current -> maxOf(current, batch.scannedThroughId) }
            val normalizedSnapshot = batch.snapshot.normalized()
            if (snapshot.getAndSet(normalizedSnapshot) != normalizedSnapshot) {
                broadcast(event(cursor.get(), "snapshot", batch.snapshot), batch.snapshot)
            }
            if (batch.events.isEmpty()) {
                delay.updateAndGet { it.multipliedBy(2).coerceAtMost(config.maxIdleInterval) }
                return
            }
            delay.set(config.pollInterval)
            batch.events.forEach { envelope ->
                cursor.set(envelope.cursor)
                broadcast(event(envelope.cursor, "audit", envelope.event), batch.snapshot)
            }
        }

        /**
         * broadcast은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
         * 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param next 업무 처리에 필요한 next 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param currentSnapshot 업무 처리에 필요한 currentSnapshot 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        private fun broadcast(next: VoucherPoolStreamEvent, currentSnapshot: VoucherPoolSnapshotResponse) {
            subscribers.toList().filter(StreamSubscription::isLive).forEach { subscriber ->
                if (!subscriber.offer(next)) subscriber.overflow(currentSnapshot)
            }
        }

        /**
         * sleep은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         */
        private fun sleep(duration: Duration): Boolean = try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos())
            true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * normalized은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun VoucherPoolSnapshotResponse.normalized(): VoucherPoolSnapshotResponse =
        copy(observedAt = Instant.EPOCH, requestId = null)
    /**
     * PollerRegistration은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property poller 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     * @property created 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     */


    private class PollerRegistration(
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val poller: ScopePoller,
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val created: Boolean,
    )
}
/**
 * VoucherPoolStreamEvent은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property cursor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property type 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property data 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property terminal 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */


internal data class VoucherPoolStreamEvent(
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: VoucherPoolEventCursor,
    /**
     * `type`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val type: String,
    /**
     * `data`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val data: String,
    /**
     * `terminal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val terminal: Boolean = false,
): Serializable {
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    private val encoded = buildString {
        append("id: ").append(cursor).append('\n')
        append("event: ").append(type).append('\n')
        append("data: ").append(data.replace("\n", "")).append("\n\n")
    }.toUtf8Bytes()

    /**
     * encode은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로 분류합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun encode(): ByteArray = encoded

    /**
     * encodedSize은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
     * 분류합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun encodedSize(): Int = encoded.size

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * maximumRevision은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun VoucherPoolSnapshotResponse.maximumRevision(): Long =
    (reservations + allocations + campaigns + batches).maxOfOrNull(VoucherPoolStreamResource::revision) ?: 0

/**
 * fallbackCursor은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun VoucherPoolSnapshotResponse.fallbackCursor(): VoucherPoolEventCursor =
    VoucherPoolEventCursor(maximumRevision(), 0)

/**
 * observedAt은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun Connection.observedAt(): Instant = createStatement().use { statement ->
    // SSE snapshot 응답의 observedAt은 PostgreSQL transaction timestamp와 일치해야 합니다.
    statement.executeQuery("SELECT transaction_timestamp()").use { result ->
        result.next()
        result.getTimestamp(1).toInstant()
    }
}

/**
 * customerNextAction은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param type 업무 처리에 필요한 type 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun customerNextAction(type: String, state: String): String = when (type to state) {
    "RESERVATION" to "ACTIVE" -> "ALLOCATE_OR_RELEASE"
    "ALLOCATION" to "ALLOCATED" -> "REVEAL_REDEEM_OR_RELEASE"
    else                      -> "REFRESH_SNAPSHOT"
}

/**
 * campaignNextAction은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun campaignNextAction(state: String): String = when (state) {
    "DRAFT" -> "UPDATE_POLICY_OR_ACTIVATE"
    "ACTIVE" -> "PAUSE_OR_REVOKE_PREVIEW"
    "PAUSED" -> "RESUME_OR_REVOKE_PREVIEW"
    else    -> "REVIEW_CAMPAIGN"
}

/**
 * batchNextAction은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun batchNextAction(state: String): String = when (state) {
    "STAGING" -> "IMPORT_CHUNK_OR_ACTIVATE"
    "ACTIVE"  -> "PAUSE_OR_REVOKE_PREVIEW"
    "PAUSED"  -> "RESUME_OR_REVOKE_PREVIEW"
    "FAILED_RETRYABLE" -> "RESUME"
    else      -> "REVIEW_BATCH"
}

/**
 * invalidEventCursor은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun invalidEventCursor(): VoucherPoolApiException =
    VoucherPoolApiException("INVALID_EVENT_CURSOR", 400, "event cursor is invalid")

/**
 * sseCapacityRejected은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun sseCapacityRejected(): VoucherPoolApiException =
    VoucherPoolApiException("SSE_CAPACITY_REJECTED", 503, "event stream capacity is unavailable", 1)

/**
 * serviceShuttingDown은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun serviceShuttingDown(): VoucherPoolApiException =
    VoucherPoolApiException("SERVICE_SHUTTING_DOWN", 503, "service is shutting down", 1)

/**
 * resolveEventCursor은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param queryCursor 업무 처리에 필요한 queryCursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param lastEventId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
internal fun resolveEventCursor(queryCursor: String?, lastEventId: String?): VoucherPoolEventCursor? {
    if (queryCursor != null && lastEventId != null && queryCursor != lastEventId) throw invalidEventCursor()
    return VoucherPoolEventCursor.parse(queryCursor ?: lastEventId)
}
