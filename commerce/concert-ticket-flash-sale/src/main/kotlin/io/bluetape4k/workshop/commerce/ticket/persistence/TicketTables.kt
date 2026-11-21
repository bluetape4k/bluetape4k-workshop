package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.exposed.core.dao.id.TimebasedUUIDTable
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityID
import io.bluetape4k.spring.data.exposed.common.annotation.ExposedEntity
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * TicketSales은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketSales: TimebasedUUIDTable("ticket_sales", "sale_id") {
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = varchar("state", 24)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val currentPolicyVersion = long("current_policy_version")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val opensAt = timestamp("opens_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val closesAt = timestamp("closes_at")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
/**
 * TicketSaleEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketSaleEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<TicketSaleEntity>(TicketSales)
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    var state by TicketSales.state
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var currentPolicyVersion by TicketSales.currentPolicyVersion
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var opensAt by TicketSales.opensAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var closesAt by TicketSales.closesAt
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketSales.revision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketSales.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by TicketSales.updatedAt
}

/**
 * TicketSalePolicies은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketSalePolicies: LongIdTable("ticket_sale_policy_versions") {
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId = javaUUID("sale_id")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val policyVersion = long("policy_version")
    /**
     * `perUserLimit`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val perUserLimit = integer("per_user_limit")
    /**
     * `maxQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxQuantity = integer("max_quantity")
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val holdSeconds = long("hold_seconds")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
}

@ExposedEntity
/**
 * TicketSalePolicyEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketSalePolicyEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketSalePolicyEntity>(TicketSalePolicies)
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var saleId by TicketSalePolicies.saleId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var policyVersion by TicketSalePolicies.policyVersion
    /**
     * `perUserLimit`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var perUserLimit by TicketSalePolicies.perUserLimit
    /**
     * `maxQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var maxQuantity by TicketSalePolicies.maxQuantity
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var holdSeconds by TicketSalePolicies.holdSeconds
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketSalePolicies.createdAt
}

/**
 * TicketInventories은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketInventories: LongIdTable("ticket_inventory") {
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId = javaUUID("sale_id")
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val grade = varchar("grade", 32)
    /**
     * `totalQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val totalQuantity = integer("total_quantity")
    /**
     * `heldQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val heldQuantity = integer("held_quantity")
    /**
     * `soldQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val soldQuantity = integer("sold_quantity")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
}

@ExposedEntity
/**
 * TicketInventoryEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketInventoryEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketInventoryEntity>(TicketInventories)
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var saleId by TicketInventories.saleId
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var grade by TicketInventories.grade
    /**
     * `totalQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var totalQuantity by TicketInventories.totalQuantity
    /**
     * `heldQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var heldQuantity by TicketInventories.heldQuantity
    /**
     * `soldQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var soldQuantity by TicketInventories.soldQuantity
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketInventories.revision

    /**
     * toRecord은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun toRecord() = InventoryRecord(saleId, grade, totalQuantity, heldQuantity, soldQuantity, revision)
}

/**
 * TicketIdentitySubjects은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketIdentitySubjects: TimebasedUUIDTable("ticket_identity_subjects", "subject_id") {
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val identityKind = varchar("identity_kind", 8)
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val anonymizedAt = timestamp("anonymized_at").nullable()
}

@ExposedEntity
/**
 * TicketIdentitySubjectEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketIdentitySubjectEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<TicketIdentitySubjectEntity>(TicketIdentitySubjects)
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    var identityKind by TicketIdentitySubjects.identityKind
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketIdentitySubjects.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var anonymizedAt by TicketIdentitySubjects.anonymizedAt
}

/**
 * TicketIdentityAliases은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketIdentityAliases: LongIdTable("ticket_identity_aliases") {
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val identityKind = varchar("identity_kind", 8)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val keyVersion = integer("key_version")
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val digest = binary("digest", 32)
    /**
     * `subjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val subjectId = javaUUID("subject_id")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
}

@ExposedEntity
/**
 * TicketIdentityAliasEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketIdentityAliasEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketIdentityAliasEntity>(TicketIdentityAliases)
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    var identityKind by TicketIdentityAliases.identityKind
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var keyVersion by TicketIdentityAliases.keyVersion
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var digest by TicketIdentityAliases.digest
    /**
     * `subjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var subjectId by TicketIdentityAliases.subjectId
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketIdentityAliases.createdAt
}

/**
 * TicketWaitingRoomEntries은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketWaitingRoomEntries: LongIdTable("ticket_waiting_room_entries") {
    /**
     * `entryId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entryId = javaUUID("entry_id")
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId = javaUUID("sale_id")
    /**
     * `userSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val userSubjectId = javaUUID("user_subject_id")
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = varchar("state", 24)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val sequence = long("sequence")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
/**
 * TicketWaitingRoomEntryEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketWaitingRoomEntryEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketWaitingRoomEntryEntity>(TicketWaitingRoomEntries)
    /**
     * `entryId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var entryId by TicketWaitingRoomEntries.entryId
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var saleId by TicketWaitingRoomEntries.saleId
    /**
     * `userSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var userSubjectId by TicketWaitingRoomEntries.userSubjectId
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var state by TicketWaitingRoomEntries.state
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var sequence by TicketWaitingRoomEntries.sequence
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketWaitingRoomEntries.revision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketWaitingRoomEntries.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by TicketWaitingRoomEntries.updatedAt

    /**
     * toRecord은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun toRecord() = WaitingEntryRecord(id.value, entryId, saleId, userSubjectId, sequence)
}

/**
 * TicketAdmissionGrants은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketAdmissionGrants: LongIdTable("ticket_admission_grants") {
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId = javaUUID("sale_id")
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val grantNonce = javaUUID("grant_nonce")
    /**
     * 호출 주체 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보는 노출하지 않습니다.
     */
    val buyerSubjectId = javaUUID("buyer_subject_id")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val policyVersion = long("policy_version")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val expiresAt = timestamp("expires_at")
    /**
     * `consumedAttemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val consumedAttemptId = javaUUID("consumed_attempt_id").nullable()
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val consumedAt = timestamp("consumed_at").nullable()
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
}

@ExposedEntity
/**
 * TicketAdmissionGrantEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketAdmissionGrantEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketAdmissionGrantEntity>(TicketAdmissionGrants)
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var saleId by TicketAdmissionGrants.saleId
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var grantNonce by TicketAdmissionGrants.grantNonce
    /**
     * 호출 주체 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보는 노출하지 않습니다.
     */
    var buyerSubjectId by TicketAdmissionGrants.buyerSubjectId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var policyVersion by TicketAdmissionGrants.policyVersion
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var expiresAt by TicketAdmissionGrants.expiresAt
    /**
     * `consumedAttemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var consumedAttemptId by TicketAdmissionGrants.consumedAttemptId
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var consumedAt by TicketAdmissionGrants.consumedAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketAdmissionGrants.createdAt
}

/**
 * TicketBuyerSaleStates은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketBuyerSaleStates: LongIdTable("ticket_buyer_sale_states") {
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId = javaUUID("sale_id")
    /**
     * `userSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val userSubjectId = javaUUID("user_subject_id")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val policyVersion = long("policy_version")
    /**
     * `purchasedQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val purchasedQuantity = integer("purchased_quantity")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
}

@ExposedEntity
/**
 * TicketBuyerSaleStateEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketBuyerSaleStateEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketBuyerSaleStateEntity>(TicketBuyerSaleStates)
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var saleId by TicketBuyerSaleStates.saleId
    /**
     * `userSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var userSubjectId by TicketBuyerSaleStates.userSubjectId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var policyVersion by TicketBuyerSaleStates.policyVersion
    /**
     * `purchasedQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var purchasedQuantity by TicketBuyerSaleStates.purchasedQuantity
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketBuyerSaleStates.revision
}

/**
 * TicketPurchaseAttempts은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketPurchaseAttempts: TimebasedUUIDTable("ticket_purchase_attempts", "attempt_id") {
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId = javaUUID("sale_id")
    /**
     * `userSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val userSubjectId = javaUUID("user_subject_id")
    /**
     * `ipSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val ipSubjectId = javaUUID("ip_subject_id")
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val grade = varchar("grade", 32)
    /**
     * `quantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quantity = integer("quantity")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val policyVersion = long("policy_version")
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = varchar("state", 40)
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val holdDeadline = timestamp("hold_deadline")
    /**
     * `authorizationOperationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val authorizationOperationId = javaUUID("authorization_operation_id")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
/**
 * TicketPurchaseAttemptEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketPurchaseAttemptEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<TicketPurchaseAttemptEntity>(TicketPurchaseAttempts)
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var saleId by TicketPurchaseAttempts.saleId
    /**
     * `userSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var userSubjectId by TicketPurchaseAttempts.userSubjectId
    /**
     * `ipSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var ipSubjectId by TicketPurchaseAttempts.ipSubjectId
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var grade by TicketPurchaseAttempts.grade
    /**
     * `quantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var quantity by TicketPurchaseAttempts.quantity
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var policyVersion by TicketPurchaseAttempts.policyVersion
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var state by TicketPurchaseAttempts.state
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var holdDeadline by TicketPurchaseAttempts.holdDeadline
    /**
     * `authorizationOperationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var authorizationOperationId by TicketPurchaseAttempts.authorizationOperationId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketPurchaseAttempts.revision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketPurchaseAttempts.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by TicketPurchaseAttempts.updatedAt
}

/**
 * TicketActiveIdentityGuards은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketActiveIdentityGuards: LongIdTable("ticket_active_identity_guards") {
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId = javaUUID("sale_id")
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val identityKind = varchar("identity_kind", 8)
    /**
     * `identitySubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val identitySubjectId = javaUUID("identity_subject_id")
    /**
     * `activeAttemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val activeAttemptId = javaUUID("active_attempt_id")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
}

@ExposedEntity
/**
 * TicketActiveIdentityGuardEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을
 * 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketActiveIdentityGuardEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketActiveIdentityGuardEntity>(TicketActiveIdentityGuards)
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var saleId by TicketActiveIdentityGuards.saleId
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var identityKind by TicketActiveIdentityGuards.identityKind
    /**
     * `identitySubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var identitySubjectId by TicketActiveIdentityGuards.identitySubjectId
    /**
     * `activeAttemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var activeAttemptId by TicketActiveIdentityGuards.activeAttemptId
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketActiveIdentityGuards.createdAt
}

/**
 * TicketOrders은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketOrders: TimebasedUUIDTable("ticket_orders", "order_id") {
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId = javaUUID("attempt_id")
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId = javaUUID("sale_id")
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val grade = varchar("grade", 32)
    /**
     * `quantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quantity = integer("quantity")
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = varchar("state", 24)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val ticketDisposition = varchar("ticket_disposition", 24)
    /**
     * `authorizationOperationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val authorizationOperationId = javaUUID("authorization_operation_id")
    /**
     * `refundOperationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val refundOperationId = javaUUID("refund_operation_id").nullable()
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
/**
 * TicketOrderEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketOrderEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<TicketOrderEntity>(TicketOrders)
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var attemptId by TicketOrders.attemptId
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var saleId by TicketOrders.saleId
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var grade by TicketOrders.grade
    /**
     * `quantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var quantity by TicketOrders.quantity
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var state by TicketOrders.state
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var ticketDisposition by TicketOrders.ticketDisposition
    /**
     * `authorizationOperationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var authorizationOperationId by TicketOrders.authorizationOperationId
    /**
     * `refundOperationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var refundOperationId by TicketOrders.refundOperationId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketOrders.revision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketOrders.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by TicketOrders.updatedAt
}

/**
 * TicketPaymentOperations은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketPaymentOperations: LongIdTable("ticket_payment_operations") {
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val provider = varchar("provider", 32)
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId = javaUUID("operation_id")
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId = javaUUID("attempt_id")
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId = javaUUID("order_id").nullable()
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val operationKind = varchar("operation_kind", 16)
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val status = varchar("status", 32)
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val nextReconcileAt = timestamp("next_reconcile_at").nullable()
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val claimToken = javaUUID("claim_token").nullable()
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val claimRevision = long("claim_revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val claimUntil = timestamp("claim_until").nullable()
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
/**
 * TicketPaymentOperationEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketPaymentOperationEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketPaymentOperationEntity>(TicketPaymentOperations)
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    var provider by TicketPaymentOperations.provider
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var operationId by TicketPaymentOperations.operationId
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var attemptId by TicketPaymentOperations.attemptId
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var orderId by TicketPaymentOperations.orderId
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var operationKind by TicketPaymentOperations.operationKind
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var status by TicketPaymentOperations.status
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var nextReconcileAt by TicketPaymentOperations.nextReconcileAt
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var claimToken by TicketPaymentOperations.claimToken
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var claimRevision by TicketPaymentOperations.claimRevision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var claimUntil by TicketPaymentOperations.claimUntil
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketPaymentOperations.revision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketPaymentOperations.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by TicketPaymentOperations.updatedAt
}

/**
 * TicketTickets은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketTickets: LongIdTable("ticket_tickets") {
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId = javaUUID("order_id")
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val externalTicketDigest = binary("external_ticket_digest", 32).nullable()
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = varchar("state", 24)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
/**
 * TicketTicketEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketTicketEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketTicketEntity>(TicketTickets)
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var orderId by TicketTickets.orderId
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var externalTicketDigest by TicketTickets.externalTicketDigest
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var state by TicketTickets.state
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketTickets.revision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketTickets.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by TicketTickets.updatedAt
}

/**
 * TicketEffectOperations은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketEffectOperations: LongIdTable("ticket_effect_operations") {
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val effectKind = varchar("effect_kind", 16)
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId = javaUUID("operation_id")
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId = javaUUID("order_id")
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val status = varchar("status", 24)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val claimToken = javaUUID("claim_token").nullable()
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val claimRevision = long("claim_revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val claimUntil = timestamp("claim_until").nullable()
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
/**
 * TicketEffectOperationEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketEffectOperationEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketEffectOperationEntity>(TicketEffectOperations)
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    var effectKind by TicketEffectOperations.effectKind
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var operationId by TicketEffectOperations.operationId
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var orderId by TicketEffectOperations.orderId
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var status by TicketEffectOperations.status
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var claimToken by TicketEffectOperations.claimToken
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var claimRevision by TicketEffectOperations.claimRevision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var claimUntil by TicketEffectOperations.claimUntil
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var revision by TicketEffectOperations.revision
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketEffectOperations.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by TicketEffectOperations.updatedAt
}

/**
 * TicketEffectReceipts은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketEffectReceipts: LongIdTable("ticket_effect_receipts") {
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val consumerName = varchar("consumer_name", 80)
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId = javaUUID("operation_id")
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val payloadDigest = binary("payload_digest", 32)
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
}

@ExposedEntity
/**
 * TicketEffectReceiptEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketEffectReceiptEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketEffectReceiptEntity>(TicketEffectReceipts)
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    var consumerName by TicketEffectReceipts.consumerName
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var operationId by TicketEffectReceipts.operationId
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var payloadDigest by TicketEffectReceipts.payloadDigest
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketEffectReceipts.createdAt
}

/**
 * TicketHttpIdempotencies은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object TicketHttpIdempotencies: LongIdTable("ticket_http_idempotency") {
    /**
     * `principalSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val principalSubjectId = javaUUID("principal_subject_id")
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val httpMethod = varchar("http_method", 8)
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val canonicalRoute = varchar("canonical_route", 128)
    /**
     * `resourceId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId = varchar("resource_id", 64)
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val operation = varchar("operation", 64)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val idempotencyKeyDigest = binary("idempotency_key_digest", 32)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val requestFingerprint = binary("request_fingerprint", 32)
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val status = varchar("status", 24)
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId = javaUUID("attempt_id").nullable()
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val responseStatus = integer("response_status").nullable()
    /**
     * 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    val responseBody = binary("response_body").nullable()
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val expiresAt = timestamp("expires_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
/**
 * TicketHttpIdempotencyEntity은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class TicketHttpIdempotencyEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TicketHttpIdempotencyEntity>(TicketHttpIdempotencies)
    /**
     * `principalSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var principalSubjectId by TicketHttpIdempotencies.principalSubjectId
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var httpMethod by TicketHttpIdempotencies.httpMethod
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var canonicalRoute by TicketHttpIdempotencies.canonicalRoute
    /**
     * `resourceId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var resourceId by TicketHttpIdempotencies.resourceId
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var operation by TicketHttpIdempotencies.operation
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var idempotencyKeyDigest by TicketHttpIdempotencies.idempotencyKeyDigest
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var requestFingerprint by TicketHttpIdempotencies.requestFingerprint
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var status by TicketHttpIdempotencies.status
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var attemptId by TicketHttpIdempotencies.attemptId
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var responseStatus by TicketHttpIdempotencies.responseStatus
    /**
     * 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    var responseBody by TicketHttpIdempotencies.responseBody
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var expiresAt by TicketHttpIdempotencies.expiresAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by TicketHttpIdempotencies.createdAt
    /**
     * 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by TicketHttpIdempotencies.updatedAt
}

/** 현재 구현된 repository boundary를 뒷받침하는 table입니다. */
/** 현재 구현된 repository boundary를 뒷받침하는 table입니다. */
val ticketAuthorityTables = arrayOf(
    TicketSales,
    TicketSalePolicies,
    TicketInventories,
    TicketIdentitySubjects,
    TicketIdentityAliases,
    TicketWaitingRoomEntries,
    TicketAdmissionGrants,
    TicketBuyerSaleStates,
    TicketPurchaseAttempts,
    TicketActiveIdentityGuards,
    TicketOrders,
    TicketPaymentOperations,
    TicketTickets,
    TicketEffectOperations,
    TicketEffectReceipts,
    TicketHttpIdempotencies,
)
