package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.*
/**
 * CampaignProjectionReadModel은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property streamVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property globalPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property allocatedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property perUserLimit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property redemptionTtlSeconds 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property startsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property fencingToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class CampaignProjectionReadModel(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: TenantId,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: CampaignState,
    /**
     * `streamVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val streamVersion: Long,
    /**
     * `globalPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val globalPosition: Long,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
    /**
     * `allocatedCount`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val allocatedCount: Int,
    /**
     * `perUserLimit`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val perUserLimit: Int,
    /**
     * `redemptionTtlSeconds`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val redemptionTtlSeconds: Long,
    /**
     * `startsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val startsAt: Instant,
    /**
     * `endsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val endsAt: Instant,
    /**
     * `fencingToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fencingToken: Long,
)

/**
 * semantic campaign state는 fenced projection coordinator를 통해서만 접근할 수 있습니다.
 * generic CRUD나 독립 transaction entry point를 의도적으로 노출하지 않습니다.
 * @property events 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */
internal class CampaignProjectionStore(
    /**
     * `events`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val events: CampaignProjectionEventDecoder = CampaignProjectionEventDecoder(),
) {
    /**
     * apply은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param envelope 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun apply(
        key: ProjectionKey,
        lease: ProjectionLease,
        envelope: EventEnvelope,
        now: Instant,
    ) {
        val event = events.decode(envelope) ?: return
        val current = findRow(key, envelope.tenantId, envelope.stream.id)
        val aggregate = current
            ?.let(::toReadModel)
            ?.toAggregate()
            ?.evolve(event)
            ?: CampaignAggregate.replay(listOf(event))

        if (current == null) insert(key, lease, envelope, aggregate, now)
        else update(key, lease, envelope, aggregate, now)
    }

    /**
     * find은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun find(
        key: ProjectionKey,
        tenantId: TenantId,
        campaignId: UUID,
    ): CampaignProjectionReadModel? = findRow(key, tenantId, campaignId)?.let(::toReadModel)

    /**
     * insert은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param envelope 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param aggregate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun insert(
        key: ProjectionKey,
        lease: ProjectionLease,
        envelope: EventEnvelope,
        aggregate: CampaignAggregate,
        now: Instant,
    ) {
        CampaignProjectionReadModels.insert { row ->
            row[projection] = key.projection
            row[generation] = key.generation
            row[tenantId] = envelope.tenantId.value
            row[campaignId] = envelope.stream.id
            write(row, envelope, aggregate, lease, now)
        }
    }

    /**
     * update은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param envelope 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param aggregate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun update(
        key: ProjectionKey,
        lease: ProjectionLease,
        envelope: EventEnvelope,
        aggregate: CampaignAggregate,
        now: Instant,
    ) {
        val whereOp = {
            campaignKeyPredicate(key, envelope.tenantId, envelope.stream.id) and
                    (CampaignProjectionReadModels.streamVersion less envelope.stream.version) and
                    (CampaignProjectionReadModels.fencingToken lessEq lease.fencingToken)
        }
        val updated = CampaignProjectionReadModels.update(whereOp) { row ->
            write(row, envelope, aggregate, lease, now)
        }
        check(updated == 1) { "campaign projection update was rejected" }
    }

    /**
     * findRow은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun findRow(
        key: ProjectionKey,
        tenantId: TenantId,
        campaignId: UUID,
    ): ResultRow? =
        CampaignProjectionReadModels
            .selectAll()
            .where { campaignKeyPredicate(key, tenantId, campaignId) }
            .singleOrNull()
}

/**
 * campaignKeyPredicate은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
 * 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
 * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun campaignKeyPredicate(
    key: ProjectionKey,
    tenantId: TenantId,
    campaignId: UUID,
) =
    (CampaignProjectionReadModels.projection eq key.projection) and
            (CampaignProjectionReadModels.generation eq key.generation) and
            (CampaignProjectionReadModels.tenantId eq tenantId.value) and
            (CampaignProjectionReadModels.campaignId eq campaignId)

/**
 * toReadModel은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun toReadModel(row: ResultRow): CampaignProjectionReadModel =
    CampaignProjectionReadModel(
        tenantId = TenantId(row[CampaignProjectionReadModels.tenantId]),
        campaignId = row[CampaignProjectionReadModels.campaignId],
        state = row[CampaignProjectionReadModels.state],
        streamVersion = row[CampaignProjectionReadModels.streamVersion],
        globalPosition = row[CampaignProjectionReadModels.globalPosition],
        policyVersion = row[CampaignProjectionReadModels.policyVersion],
        capacity = row[CampaignProjectionReadModels.capacity],
        allocatedCount = row[CampaignProjectionReadModels.allocatedCount],
        perUserLimit = row[CampaignProjectionReadModels.perUserLimit],
        redemptionTtlSeconds = row[CampaignProjectionReadModels.redemptionTtlSeconds],
        startsAt = row[CampaignProjectionReadModels.startsAt],
        endsAt = row[CampaignProjectionReadModels.endsAt],
        fencingToken = row[CampaignProjectionReadModels.fencingToken],
    )

/**
 * toAggregate은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun CampaignProjectionReadModel.toAggregate(): CampaignAggregate =
    CampaignAggregate(
        tenantId = tenantId,
        campaignId = campaignId,
        state = state,
        startsAt = startsAt,
        endsAt = endsAt,
        capacity = capacity,
        allocatedCount = allocatedCount,
        perUserLimit = perUserLimit,
        redemptionTtlSeconds = redemptionTtlSeconds,
        policyVersion = policyVersion,
        version = streamVersion,
    )

/**
 * write은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param envelope 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
 * @param aggregate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun write(
    row: UpdateBuilder<*>,
    envelope: EventEnvelope,
    aggregate: CampaignAggregate,
    lease: ProjectionLease,
    now: Instant,
) {
    row[CampaignProjectionReadModels.state] = aggregate.state
    row[CampaignProjectionReadModels.streamVersion] = envelope.stream.version
    row[CampaignProjectionReadModels.globalPosition] = envelope.globalPosition
    row[CampaignProjectionReadModels.policyVersion] = aggregate.policyVersion
    row[CampaignProjectionReadModels.capacity] = aggregate.capacity
    row[CampaignProjectionReadModels.allocatedCount] = aggregate.allocatedCount
    row[CampaignProjectionReadModels.perUserLimit] = aggregate.perUserLimit
    row[CampaignProjectionReadModels.redemptionTtlSeconds] = aggregate.redemptionTtlSeconds
    row[CampaignProjectionReadModels.startsAt] = aggregate.startsAt.checkNotNull { "startsAt is null" }
    row[CampaignProjectionReadModels.endsAt] = aggregate.endsAt.checkNotNull { "endsAt is null" }
    row[CampaignProjectionReadModels.fencingToken] = lease.fencingToken
    row[CampaignProjectionReadModels.updatedAt] = now
}
