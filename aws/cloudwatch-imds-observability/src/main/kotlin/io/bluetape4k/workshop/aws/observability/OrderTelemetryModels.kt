package io.bluetape4k.workshop.aws.observability

import java.io.Serializable

/**
 * 로컬 주문 텔레메트리 이벤트 발행 요청 본문입니다.
 */
data class OrderTelemetryRequest(
    val eventId: String = "local-order",
    val outcome: TelemetryOutcome = TelemetryOutcome.SUCCESS,
    val message: String = "accepted",
    val includeMetadata: Boolean = false,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8240441594879902134L
    }
}

/**
 * 이벤트를 수락한 관측성 경계를 보여 주는 보고서입니다.
 */
data class OrderTelemetryReport(
    val outcome: TelemetryOutcome,
    val metric: PublishStatus,
    val logs: PublishStatus,
    val meterSnapshot: PublishStatus,
    val metadata: MetadataSnapshot,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8682049839979115067L
    }
}

/**
 * 메트릭 태그 값으로 사용하는 비즈니스 결과입니다.
 */
enum class TelemetryOutcome(val tagValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
}

/**
 * 단일 AWS 경계의 로컬 발행 상태입니다.
 */
enum class PublishState {
    PUBLISHED,
    FAILED,
    SKIPPED,
}

/**
 * CloudWatch 메트릭, 로그 또는 meter 스냅샷 발행 결과입니다.
 */
data class PublishStatus(
    val state: PublishState,
    val message: String = "",
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8352173228614796523L

        /**
         * 발행 완료 상태를 만듭니다.
         */
        fun published(message: String = ""): PublishStatus =
            PublishStatus(PublishState.PUBLISHED, message)

        /**
         * 하위 경계 예외로 실패 상태를 만듭니다.
         */
        fun failed(error: Throwable): PublishStatus =
            PublishStatus(PublishState.FAILED, error.message ?: error::class.simpleName.orEmpty())

        /**
         * 비활성화되었거나 의존 경계 때문에 건너뛴 상태를 만듭니다.
         */
        fun skipped(message: String = ""): PublishStatus =
            PublishStatus(PublishState.SKIPPED, message)
    }
}

/**
 * 워크숍이 노출하는 EC2 메타데이터의 안전한 하위 집합입니다.
 */
data class MetadataSnapshot(
    val state: PublishState,
    val instanceId: String = "",
    val region: String = "",
    val availabilityZone: String = "",
    val message: String = "",
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -3970765835872248833L

        /**
         * 메타데이터 조회가 비활성화되었을 때 건너뛴 메타데이터 스냅샷을 만듭니다.
         */
        fun skipped(): MetadataSnapshot =
            MetadataSnapshot(PublishState.SKIPPED, message = "metadata lookup disabled")

        /**
         * IMDS 경계 예외로 실패한 메타데이터 스냅샷을 만듭니다.
         */
        fun failed(error: Throwable): MetadataSnapshot =
            MetadataSnapshot(PublishState.FAILED, message = error.message ?: error::class.simpleName.orEmpty())
    }
}

/**
 * REST 컨트롤러 advice가 반환하는 오류 페이로드입니다.
 */
data class TelemetryErrorResponse(
    val error: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 2403698664729277661L
    }
}
