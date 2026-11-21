package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `LocalTimeRejection`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class LocalTimeRejection {
    /**
     * `LocalTimeRejection.DST_GAP`은 `dst gap` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    DST_GAP,
    /**
     * `LocalTimeRejection.DST_OVERLAP`은 `dst overlap` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한 뒤
     * 사용합니다.
     */
    DST_OVERLAP,
    /**
     * `LocalTimeRejection.OFFSET_MISMATCH`은 서로 다른 시스템·revision·증빙의 값이 일치하지 않아 자동 반영 대신 대사가 필요한
     * 값입니다.
     */
    OFFSET_MISMATCH
}

/**
 * LocalTimeResolution은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface LocalTimeResolution: Serializable {
/**
 * Resolved은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property instant 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property offset 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


    data class Resolved(
        /**
         * `instant`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val instant: Instant,
        /**
         * `offset`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val offset: ZoneOffset,
    ): LocalTimeResolution {
        companion object {
            /**
             * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
/**
 * Rejected은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property reason 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Rejected(
        /**
         * `reason`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val reason: LocalTimeRejection,
    ): LocalTimeResolution {
        companion object {
            /**
             * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
}

/** DST overlap 동안 명시적 offset을 요구해 local-time ambiguity가 reservation command에 들어오지 못하게 합니다. */
/** DST overlap 동안 명시적 offset을 요구해 local-time ambiguity가 reservation command에 들어오지 못하게 합니다. */
internal object ReservationTimePolicy: KLogging() {

    /**
     * project은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param instant 업무 처리에 필요한 instant 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param zoneId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun project(
        instant: Instant,
        zoneId: ZoneId,
    ): OffsetDateTime =
        instant.atZone(zoneId).toOffsetDateTime()

    /**
     * resolve은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param localDateTime 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param zoneId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param preferredOffset 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun resolve(
        localDateTime: LocalDateTime,
        zoneId: ZoneId,
        preferredOffset: ZoneOffset? = null,
    ): LocalTimeResolution {
        /**
         * `validOffsets`은 예약 제어면에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        val validOffsets = zoneId.rules.getValidOffsets(localDateTime)
        if (validOffsets.isEmpty()) {
            log.debug { "reservation_local_time_rejected reason=DST_GAP" }
            return LocalTimeResolution.Rejected(LocalTimeRejection.DST_GAP)
        }
        if (validOffsets.size > 1 && preferredOffset == null) {
            log.debug { "reservation_local_time_rejected reason=DST_OVERLAP" }
            return LocalTimeResolution.Rejected(LocalTimeRejection.DST_OVERLAP)
        }
        /**
         * `offset`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val offset = preferredOffset ?: validOffsets.single()
        if (offset !in validOffsets) {
            log.debug { "reservation_local_time_rejected reason=OFFSET_MISMATCH" }
            return LocalTimeResolution.Rejected(LocalTimeRejection.OFFSET_MISMATCH)
        }
        return LocalTimeResolution.Resolved(localDateTime.toInstant(offset), offset)
    }
}
