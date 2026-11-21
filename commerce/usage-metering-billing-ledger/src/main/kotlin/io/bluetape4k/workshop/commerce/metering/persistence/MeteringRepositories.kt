package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.workshop.commerce.metering.domain.CommandReceiptStatus
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptScope
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptSnapshot
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*
/**
 * CommandReceiptOwnerInsert은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property scope 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property requestFingerprint 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를
 * 확인합니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property now 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property leaseDeadline 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property retentionDeadline 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 */


data class CommandReceiptOwnerInsert(
    /**
     * `scope`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scope: CommandReceiptScope,
    /**
     * `requestFingerprint`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val requestFingerprint: String,
    /**
     * `ownerToken`은 사용량 과금·정산에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: UUID,
    /**
     * `now`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val now: Instant,
    /**
     * `leaseDeadline`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val leaseDeadline: Instant,
    /**
     * `retentionDeadline`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val retentionDeadline: Instant,
)
/**
 * CommandReceiptCompletion은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property receiptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property status 현재 사용량 계량·청구 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property httpStatus 현재 사용량 계량·청구 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property response 사용량 계량·청구 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property now 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property retentionDeadline 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 */


data class CommandReceiptCompletion(
    /**
     * `receiptId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val receiptId: UUID,
    /**
     * `ownerToken`은 사용량 과금·정산에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: UUID,
    /**
     * `status`은 사용량 과금·정산에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: CommandReceiptStatus,
    /**
     * `httpStatus`은 사용량 과금·정산에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val httpStatus: Int,
    /**
     * `response`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val response: String,
    /**
     * `now`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val now: Instant,
    /**
     * `retentionDeadline`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val retentionDeadline: Instant,
)
/**
 * MeterInsert은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property meterCode 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property unit 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property description 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property now 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 */


data class MeterInsert(
    /**
     * `id`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: UUID,
    /**
     * `tenantId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `meterCode`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val meterCode: String,
    /**
     * `unit`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val unit: String,
    /**
     * `description`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val description: String?,
    /**
     * `now`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val now: Instant,
)
/**
 * UsageCloseQuery은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property startsAt 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property cutoff 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property lastOccurredAt 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property lastId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property limit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 */


data class UsageCloseQuery(
    /**
     * `tenantId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `startsAt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val startsAt: Instant,
    /**
     * `endsAt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val endsAt: Instant,
    /**
     * `cutoff`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cutoff: Instant,
    /**
     * `lastOccurredAt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val lastOccurredAt: Instant?,
    /**
     * `lastId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val lastId: UUID?,
    /**
     * `limit`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val limit: Int,
)

@Repository
/**
 * CommandReceiptRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class CommandReceiptRepository:
    MeteringExposedJdbcRepository<CommandReceiptEntity, UUID>(CommandReceiptEntity::class) {

    /**
     * insertOwnerIfAbsent은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun insertOwnerIfAbsent(request: CommandReceiptOwnerInsert): Boolean =
        CommandReceipts.insertIgnore {
            it[tenantId] = request.scope.tenantId
            it[operation] = request.scope.operation
            it[keyDigest] = request.scope.keyDigest
            it[requestFingerprint] = request.requestFingerprint
            it[status] = CommandReceiptStatus.IN_PROGRESS.name
            it[ownerToken] = request.ownerToken
            it[leaseDeadline] = request.leaseDeadline
            it[retentionDeadline] = request.retentionDeadline
            it[createdAt] = request.now
            it[updatedAt] = request.now
        }.insertedCount == 1

    /**
     * find은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun find(scope: CommandReceiptScope): CommandReceiptSnapshot? =
        CommandReceiptEntity
            .find {
                (CommandReceipts.tenantId eq scope.tenantId) and
                        (CommandReceipts.operation eq scope.operation) and
                        (CommandReceipts.keyDigest eq scope.keyDigest)
            }
            .firstOrNull()
            ?.toSnapshot()

    /**
     * takeover은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param current 업무 처리에 필요한 current 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param ownerToken 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param leaseDeadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param retentionDeadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun takeover(
        current: CommandReceiptSnapshot,
        ownerToken: UUID,
        now: Instant,
        leaseDeadline: Instant,
        retentionDeadline: Instant,
    ): Boolean =
        CommandReceipts.update(
            where = {
                (CommandReceipts.id eq current.id) and
                        (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name) and
                        (CommandReceipts.ownerToken eq current.ownerToken) and
                        (CommandReceipts.leaseDeadline eq current.leaseDeadline)
            },
        ) {
            it[CommandReceipts.ownerToken] = ownerToken
            it[CommandReceipts.leaseDeadline] = leaseDeadline
            it[CommandReceipts.retentionDeadline] = retentionDeadline
            it[updatedAt] = now
        } == 1

    /**
     * complete은 사용량 계량·청구의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param completion 업무 처리에 필요한 completion 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun complete(completion: CommandReceiptCompletion): Boolean =
        CommandReceipts.update(
            where = {
                (CommandReceipts.id eq completion.receiptId) and
                        (CommandReceipts.ownerToken eq completion.ownerToken) and
                        (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name)
            },
        ) {
            it[status] = completion.status.name
            it[terminalAt] = completion.now
            it[terminalHttpStatus] = completion.httpStatus
            it[terminalResponse] = completion.response
            it[retentionDeadline] = completion.retentionDeadline
            it[updatedAt] = completion.now
        } == 1

    /**
     * deleteExpiredTerminalBatch은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은
     * 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun deleteExpiredTerminalBatch(now: Instant, limit: Int): Int {
        val ids = CommandReceipts
            .selectAll()
            .where {
                ((CommandReceipts.status eq CommandReceiptStatus.SUCCEEDED.name) or
                        (CommandReceipts.status eq CommandReceiptStatus.FAILED.name)) and
                        (CommandReceipts.retentionDeadline less now)
            }
            .orderBy(CommandReceipts.retentionDeadline to SortOrder.ASC, CommandReceipts.id to SortOrder.ASC)
            .limit(limit)
            .map { it[CommandReceipts.id] }

        if (ids.isEmpty()) return 0
        return CommandReceipts.deleteWhere {
            (CommandReceipts.id inList ids) and
                    ((CommandReceipts.status eq CommandReceiptStatus.SUCCEEDED.name) or
                            (CommandReceipts.status eq CommandReceiptStatus.FAILED.name)) and
                    (CommandReceipts.retentionDeadline less now)
        }
    }

    /**
     * toSnapshot은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun CommandReceiptEntity.toSnapshot(): CommandReceiptSnapshot =
        CommandReceiptSnapshot(
            id = id.value,
            requestFingerprint = requestFingerprint,
            status = CommandReceiptStatus.valueOf(status),
            ownerToken = ownerToken,
            leaseDeadline = leaseDeadline,
            terminalHttpStatus = terminalHttpStatus,
            terminalResponse = terminalResponse,
        )
}

@Repository
/**
 * MeterRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class MeterRepository: MeteringExposedJdbcRepository<MeterEntity, UUID>(MeterEntity::class) {
    /**
     * find은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param meterCode 업무 처리에 필요한 meterCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun find(tenantId: String, meterCode: String): MeterEntity? =
        MeterEntity
            .find { (Meters.tenantId eq tenantId) and (Meters.meterCode eq meterCode) }
            .firstOrNull()

    /**
     * createIfAbsent은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun createIfAbsent(request: MeterInsert): Boolean =
        Meters.insertIgnore {
            it[Meters.id] = request.id
            it[tenantId] = request.tenantId
            it[meterCode] = request.meterCode
            it[unit] = request.unit
            it[description] = request.description
            it[active] = true
            it[createdAt] = request.now
        }.insertedCount == 1
}

@Repository
/**
 * PricingScheduleRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class PricingScheduleRepository:
    MeteringExposedJdbcRepository<PricingScheduleEntity, UUID>(PricingScheduleEntity::class) {

    /**
     * getOrCreate은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param meterId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param currency 업무 처리에 필요한 currency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun getOrCreate(
        id: UUID,
        tenantId: String,
        meterId: UUID,
        currency: String,
        now: Instant,
    ): PricingScheduleEntity {
        PricingSchedules.insertIgnore {
            it[PricingSchedules.id] = id
            it[PricingSchedules.tenantId] = tenantId
            it[PricingSchedules.meterId] = meterId
            it[PricingSchedules.currency] = currency
            it[createdAt] = now
        }
        PricingSchedules
            .selectAll()
            .where {
                (PricingSchedules.tenantId eq tenantId) and
                        (PricingSchedules.meterId eq meterId) and
                        (PricingSchedules.currency eq currency)
            }
            .forUpdate()
            .single()

        return PricingScheduleEntity
            .find {
                (PricingSchedules.tenantId eq tenantId) and
                        (PricingSchedules.meterId eq meterId) and
                        (PricingSchedules.currency eq currency)
            }
            .single()
    }
}

@Repository
/**
 * PriceVersionRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class PriceVersionRepository
    : AppendOnlyMeteringExposedJdbcRepository<PriceVersionEntity, UUID>(PriceVersionEntity::class) {
    
    /**
     * timeline은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scheduleId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun timeline(scheduleId: UUID): List<PriceVersionEntity> =
        PriceVersionEntity.find { PriceVersions.scheduleId eq scheduleId }
            .orderBy(PriceVersions.effectiveFrom to SortOrder.ASC)
            .toList()

    /**
     * select은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param meterId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param currency 업무 처리에 필요한 currency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param occurredAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun select(tenantId: String, meterId: UUID, currency: String, occurredAt: Instant): PriceVersionEntity? =
        PriceVersionEntity
            .find {
                (PriceVersions.tenantId eq tenantId) and
                        (PriceVersions.meterId eq meterId) and
                        (PriceVersions.currency eq currency) and
                        (PriceVersions.effectiveFrom lessEq occurredAt) and
                        ((PriceVersions.effectiveTo greater occurredAt) or PriceVersions.effectiveTo.isNull())
            }
            .orderBy(PriceVersions.effectiveFrom to SortOrder.DESC)
            .firstOrNull()

    /**
     * isReferenced은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param priceVersionId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun isReferenced(priceVersionId: UUID): Boolean =
        LedgerEntries
            .selectAll()
            .where { LedgerEntries.priceVersionId eq priceVersionId }
            .limit(1)
            .any()
}

@Repository
/**
 * UsageEventRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsageEventRepository:
    AppendOnlyMeteringExposedJdbcRepository<UsageEventEntity, UUID>(UsageEventEntity::class) {
    /**
     * findSource은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param sourceSystem 업무 처리에 필요한 sourceSystem 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param sourceEventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findSource(tenantId: String, sourceSystem: String, sourceEventId: String): UsageEventEntity? =
        UsageEventEntity
            .find {
                (UsageEvents.tenantId eq tenantId) and
                        (UsageEvents.sourceSystem eq sourceSystem) and
                        (UsageEvents.sourceEventId eq sourceEventId)
            }
            .firstOrNull()

    /**
     * closeBatch은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param query 업무 처리에 필요한 query 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun closeBatch(query: UsageCloseQuery): List<UsageEventEntity> =
        UsageEventEntity
            .find {
                /**
                 * `afterCheckpoint`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
                 *
                 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
                 */
                val afterCheckpoint = if (query.lastOccurredAt == null || query.lastId == null) {
                    UsageEvents.occurredAt greaterEq query.startsAt
                } else {
                    (UsageEvents.occurredAt greater query.lastOccurredAt) or
                            ((UsageEvents.occurredAt eq query.lastOccurredAt) and (UsageEvents.id greater query.lastId))
                }

                (UsageEvents.tenantId eq query.tenantId) and
                        (UsageEvents.occurredAt greaterEq query.startsAt) and
                        (UsageEvents.occurredAt less query.endsAt) and
                        (UsageEvents.receivedAt lessEq query.cutoff) and afterCheckpoint
            }
            .orderBy(UsageEvents.occurredAt to SortOrder.ASC, UsageEvents.id to SortOrder.ASC)
            .limit(query.limit)
            .toList()
}

@Repository
/**
 * BillingCalendarRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingCalendarRepository:
    MeteringExposedJdbcRepository<BillingCalendarEntity, UUID>(BillingCalendarEntity::class)

@Repository
/**
 * BillingPeriodRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingPeriodRepository:
    MeteringExposedJdbcRepository<BillingPeriodEntity, UUID>(BillingPeriodEntity::class) {

    /**
     * findTenant은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    fun findTenant(id: UUID, tenantId: String): BillingPeriodEntity? =
        BillingPeriodEntity
            .find {
                (BillingPeriods.id eq id) and (BillingPeriods.tenantId eq tenantId)
            }
            .firstOrNull()

    /**
     * overlapping은 사용량 계량·청구의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param calendarId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param startsAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param endsAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun overlapping(calendarId: UUID, startsAt: Instant, endsAt: Instant): Boolean =
        BillingPeriods
            .selectAll()
            .where {
                (BillingPeriods.calendarId eq calendarId) and
                        (BillingPeriods.startsAt less endsAt) and
                        (BillingPeriods.endsAt greater startsAt)
            }
            .limit(1)
            .any()

    /**
     * openAt은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param currency 업무 처리에 필요한 currency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param instant 업무 처리에 필요한 instant 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun openAt(tenantId: String, currency: String, instant: Instant): BillingPeriodEntity? =
        BillingPeriodEntity
            .find {
                (BillingPeriods.tenantId eq tenantId) and
                        (BillingPeriods.currency eq currency) and
                        (BillingPeriods.state eq "OPEN") and
                        (BillingPeriods.startsAt lessEq instant) and
                        (BillingPeriods.endsAt greater instant)
            }
            .firstOrNull()
}

@Repository
/**
 * CloseRunRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class CloseRunRepository
    : MeteringExposedJdbcRepository<CloseRunEntity, UUID>(CloseRunEntity::class) {

    /**
     * findTenant은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    fun findTenant(id: UUID, tenantId: String): CloseRunEntity? =
        CloseRunEntity
            .find { (CloseRuns.id eq id) and (CloseRuns.tenantId eq tenantId) }
            .firstOrNull()

    /**
     * running은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun running(limit: Int): List<CloseRunEntity> =
        CloseRunEntity
            .find { CloseRuns.state eq "RUNNING" }
            .orderBy(CloseRuns.updatedAt to SortOrder.ASC, CloseRuns.id to SortOrder.ASC)
            .limit(limit)
            .toList()
}

@Repository
/**
 * LedgerEntryRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class LedgerEntryRepository
    : AppendOnlyMeteringExposedJdbcRepository<LedgerEntryEntity, UUID>(LedgerEntryEntity::class) {

    /**
     * forPostingPeriod은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param periodId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun forPostingPeriod(tenantId: String, periodId: UUID): List<LedgerEntryEntity> =
        LedgerEntryEntity
            .find {
                (LedgerEntries.tenantId eq tenantId) and (LedgerEntries.postingPeriodId eq periodId)
            }
            .orderBy(LedgerEntries.id to SortOrder.ASC)
            .toList()

    /**
     * findSource은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param type 업무 처리에 필요한 type 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param sourceType 업무 처리에 필요한 sourceType 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param sourceId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findSource(tenantId: String, type: String, sourceType: String, sourceId: String): LedgerEntryEntity? =
        LedgerEntryEntity
            .find {
                (LedgerEntries.tenantId eq tenantId) and
                        (LedgerEntries.entryType eq type) and
                        (LedgerEntries.sourceReferenceType eq sourceType) and
                        (LedgerEntries.sourceReferenceId eq sourceId)
            }
            .firstOrNull()
}

@Repository
/**
 * InvoiceRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class InvoiceRepository
    : AppendOnlyMeteringExposedJdbcRepository<InvoiceEntity, UUID>(InvoiceEntity::class) {
    /**
     * findPeriod은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param periodId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param currency 업무 처리에 필요한 currency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun findPeriod(tenantId: String, periodId: UUID, currency: String): InvoiceEntity? =
        InvoiceEntity
            .find {
                (Invoices.tenantId eq tenantId) and
                        (Invoices.periodId eq periodId) and
                        (Invoices.currency eq currency)
            }
            .firstOrNull()
}

@Repository
/**
 * InvoiceLineRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class InvoiceLineRepository:
    AppendOnlyMeteringExposedJdbcRepository<InvoiceLineEntity, UUID>(InvoiceLineEntity::class)

@Repository
/**
 * InvoiceLineEntryRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class InvoiceLineEntryRepository:
    AppendOnlyMeteringExposedJdbcRepository<InvoiceLineEntryEntity, UUID>(InvoiceLineEntryEntity::class)

@Repository
/**
 * ReconciliationRunRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ReconciliationRunRepository:
    MeteringExposedJdbcRepository<ReconciliationRunEntity, UUID>(ReconciliationRunEntity::class)

@Repository
/**
 * ReconciliationFindingRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ReconciliationFindingRepository
    : AppendOnlyMeteringExposedJdbcRepository<ReconciliationFindingEntity, UUID>(ReconciliationFindingEntity::class)
