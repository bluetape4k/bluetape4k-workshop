package io.bluetape4k.workshop.idgenerator.model

import java.io.Serializable

/**
 * Snowflake ID 응답 — Long ID와 파싱된 구성요소 포함.
 */
data class SnowflakeResponse(
    val id: Long,
    val idAsString: String,
    val timestamp: Long,
    val machineId: Int,
    val sequence: Int,
) : Serializable

/**
 * String ID (ULID / KSUID) 응답.
 */
data class StringIdResponse(
    val type: String,
    val id: String,
) : Serializable

/**
 * Hashids 인코딩 응답.
 */
data class HashidsResponse(
    val numbers: List<Long>,
    val encoded: String,
    val decoded: List<Long>,
) : Serializable

/**
 * 일괄 ID 생성 응답.
 */
data class BatchIdResponse(
    val type: String,
    val count: Int,
    val ids: List<String>,
) : Serializable
