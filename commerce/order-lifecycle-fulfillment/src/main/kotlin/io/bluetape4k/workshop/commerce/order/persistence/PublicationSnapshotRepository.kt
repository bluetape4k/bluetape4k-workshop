package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.Min
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
/**
 * PublicationStatusSummary은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property published 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property processing 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property failed 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property resubmitted 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property completed 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property oldestIncomplete 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 */


internal data class PublicationStatusSummary(
    /**
     * `published`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val published: Int,
    /**
     * `processing`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val processing: Int,
    /**
     * `failed`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val failed: Int,
    /**
     * `resubmitted`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val resubmitted: Int,
    /**
     * `completed`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val completed: Int,
    /**
     * `oldestIncomplete`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val oldestIncomplete: Instant?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * event payload를 materialize하지 않고 publication operations data를 읽습니다.
 *
 * status와 completion presence로 group 처리해 Spring Modulith publication repository가 사용하는
 * incomplete-publication predicate를 보존하면서 결과를 status별 최대 두 row로 제한합니다.
 * @property table 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Repository
internal class PublicationSnapshotRepository(
    @param:Qualifier("eventPublicationTable")
    /**
     * `table`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val table: ExposedEventPublicationTable,
) {

    companion object: KLogging()

    /**
     * snapshot은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를
     * 추정하지 않습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun snapshot(): PublicationStatusSummary {
        val publicationCount: Count = table.id.count()
        val oldestPublication: Min<Instant, Instant> = table.publicationDate.min()
        val completionMissing: Op<Boolean> = table.completionDate.isNull()
        val counts: MutableMap<Status, Long> = EventPublication.Status.entries
            .associateWith { 0L }
            .toMutableMap()
        var oldestIncomplete: Instant? = null

        table
            .select(table.status, completionMissing, publicationCount, oldestPublication)
            .groupBy(table.status, completionMissing)
            .forEach { row ->
                val status = row[table.status]
                if (status != null) counts[status] = counts.getValue(status) + row[publicationCount]

                if (row[completionMissing] || status != EventPublication.Status.COMPLETED) {
                    row[oldestPublication]?.let { candidate ->
                        if (oldestIncomplete == null || candidate < oldestIncomplete) {
                            oldestIncomplete = candidate
                        }
                    }
                }
            }

        return PublicationStatusSummary(
            published = counts.getValue(EventPublication.Status.PUBLISHED).toInt(),
            processing = counts.getValue(EventPublication.Status.PROCESSING).toInt(),
            failed = counts.getValue(EventPublication.Status.FAILED).toInt(),
            resubmitted = counts.getValue(EventPublication.Status.RESUBMITTED).toInt(),
            completed = counts.getValue(EventPublication.Status.COMPLETED).toInt(),
            oldestIncomplete = oldestIncomplete
        )
    }
}
