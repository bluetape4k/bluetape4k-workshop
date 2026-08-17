package io.bluetape4k.workshop.aws.kinesis

import java.io.Serializable

/** Kinesis 데모에서 전송하는 제한된 JSON 이벤트입니다. */
data class KinesisEvent(
    val eventId: String,
    val partitionKey: String,
    val ordinal: Int,
    val payload: String,
) : Serializable {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank." }
        require(partitionKey.isNotBlank()) { "partitionKey must not be blank." }
        require(ordinal >= 0) { "ordinal must not be negative." }
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 스트림 readiness 상태를 외부에 노출할 때 사용하는 allowlist입니다. */
enum class KinesisStreamStatus {
    ACTIVE,
    CREATING,
    FAILED,
}

/** 스트림 readiness 결과입니다. 원시 AWS 응답이나 credential은 포함하지 않습니다. */
data class KinesisStreamReadiness(
    val status: KinesisStreamStatus,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 하나의 Kinesis record publish 결과입니다. */
data class KinesisPublishReport(
    val ordinal: Int,
    val sequenceNumber: String,
    val shardId: String,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** JSON으로 복원한 consume 결과입니다. */
data class KinesisConsumedRecord(
    val sequenceNumber: String,
    val partitionKey: String,
    val event: KinesisEvent,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** runner가 출력에 사용할 비밀값 없는 요약입니다. */
data class KinesisDemoResult(
    val publishedCount: Int,
    val consumedCount: Int,
    val sequenceNumbers: List<String>,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
