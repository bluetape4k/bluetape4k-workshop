package io.bluetape4k.workshop.idgenerator.model

import java.io.Serializable

/**
 * Response for a generated Snowflake ID and its parsed components.
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
 * Response for string-based IDs such as ULID and KSUID.
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
 * Response for Hashids encoding and decoding.
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
 * Response for batch ID generation.
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
