package io.bluetape4k.workshop.idgenerator.model

import java.io.Serializable

/**
 * 생성된 Snowflake ID 와 parsing 된 component 를 담는 response 입니다.
 */
data class SnowflakeResponse(
    val id: Long,
    val idAsString: String,
    val timestamp: Long,
    val machineId: Int,
    val sequence: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * ULID, KSUID 같은 string 기반 ID 를 담는 response 입니다.
 */
data class StringIdResponse(
    val type: String,
    val id: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Hashids encode/decode 결과를 담는 response 입니다.
 */
data class HashidsResponse(
    val numbers: List<Long>,
    val encoded: String,
    val decoded: List<Long>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * batch ID generation 결과를 담는 response 입니다.
 */
data class BatchIdResponse(
    val type: String,
    val count: Int,
    val ids: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
