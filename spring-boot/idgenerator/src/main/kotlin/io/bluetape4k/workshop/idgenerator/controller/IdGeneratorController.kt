package io.bluetape4k.workshop.idgenerator.controller

import io.bluetape4k.idgenerators.hashids.Hashids
import io.bluetape4k.idgenerators.ksuid.KsuidGenerator
import io.bluetape4k.idgenerators.snowflake.Snowflakers
import io.bluetape4k.idgenerators.ulid.UlidGenerator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.idgenerator.model.BatchIdResponse
import io.bluetape4k.workshop.idgenerator.model.HashidsResponse
import io.bluetape4k.workshop.idgenerator.model.SnowflakeResponse
import io.bluetape4k.workshop.idgenerator.model.StringIdResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * bluetape4k-idgenerators 의 4가지 ID 생성기를 REST API로 노출합니다.
 *
 * - Snowflake: Long 형 시간 기반 분산 ID (Twitter 알고리즘)
 * - ULID: 26자 Crockford Base32, 시간순 정렬 가능 문자열 ID
 * - KSUID: 27자 Base62, 초(seconds) 기반 정렬 가능 문자열 ID
 * - Hashids: 숫자 배열을 URL-safe 문자열로 인코딩/디코딩
 */
@RestController
@RequestMapping("/ids")
class IdGeneratorController {

    companion object : KLogging() {
        private val snowflake = Snowflakers.Default
        private val ulidGenerator = UlidGenerator()
        private val ksuidGenerator = KsuidGenerator()
        private val hashids = Hashids(salt = "bluetape4k-workshop", minHashLength = 8)
    }

    // ── Snowflake ──────────────────────────────────────────────────────────

    /**
     * Snowflake ID 하나를 생성합니다.
     *
     * Long 값과 파싱된 구성 요소(timestamp, machineId, sequence)를 함께 반환합니다.
     */
    @GetMapping("/snowflake")
    suspend fun snowflakeId(): SnowflakeResponse {
        val id = snowflake.nextId()
        val parsed = snowflake.parse(id)
        log.debug { "Generated Snowflake id=$id, machineId=${parsed.machineId}" }
        return SnowflakeResponse(
            id = id,
            idAsString = snowflake.nextIdAsString(),
            timestamp = parsed.timestamp,
            machineId = parsed.machineId,
            sequence = parsed.sequence,
        )
    }

    /**
     * 기존 Snowflake Long ID를 파싱하여 구성 요소를 반환합니다.
     */
    @GetMapping("/snowflake/parse/{id}")
    suspend fun parseSnowflakeId(@PathVariable id: Long): SnowflakeResponse {
        val parsed = snowflake.parse(id)
        log.debug { "Parsed Snowflake id=$id → $parsed" }
        return SnowflakeResponse(
            id = id,
            idAsString = id.toString(36),
            timestamp = parsed.timestamp,
            machineId = parsed.machineId,
            sequence = parsed.sequence,
        )
    }

    /**
     * Snowflake ID를 [count]개 일괄 생성합니다. (최대 1000개)
     */
    @GetMapping("/snowflake/batch")
    suspend fun snowflakeBatch(
        @RequestParam(defaultValue = "10") count: Int,
    ): BatchIdResponse {
        val safeCount = count.coerceIn(1, 1000)
        val ids = snowflake.nextIds(safeCount).map { it.toString() }.toList()
        log.debug { "Generated $safeCount Snowflake IDs" }
        return BatchIdResponse(type = "snowflake", count = safeCount, ids = ids)
    }

    // ── ULID ───────────────────────────────────────────────────────────────

    /**
     * ULID를 생성합니다.
     *
     * 26자 Crockford Base32 인코딩, 사전순 정렬 시 시간 순서가 보장됩니다.
     */
    @GetMapping("/ulid")
    suspend fun ulidId(): StringIdResponse {
        val id = ulidGenerator.nextId()
        log.debug { "Generated ULID id=$id" }
        return StringIdResponse(type = "ulid", id = id)
    }

    /**
     * ULID를 [count]개 일괄 생성합니다. (최대 1000개)
     */
    @GetMapping("/ulid/batch")
    suspend fun ulidBatch(
        @RequestParam(defaultValue = "10") count: Int,
    ): BatchIdResponse {
        val safeCount = count.coerceIn(1, 1000)
        val ids = List(safeCount) { ulidGenerator.nextId() }
        return BatchIdResponse(type = "ulid", count = safeCount, ids = ids)
    }

    // ── KSUID ──────────────────────────────────────────────────────────────

    /**
     * KSUID를 생성합니다.
     *
     * 27자 Base62 인코딩, 초(seconds) 기반으로 정렬 가능합니다.
     */
    @GetMapping("/ksuid")
    suspend fun ksuidId(): StringIdResponse {
        val id = ksuidGenerator.nextId()
        log.debug { "Generated KSUID id=$id" }
        return StringIdResponse(type = "ksuid", id = id)
    }

    /**
     * KSUID를 [count]개 일괄 생성합니다. (최대 1000개)
     */
    @GetMapping("/ksuid/batch")
    suspend fun ksuidBatch(
        @RequestParam(defaultValue = "10") count: Int,
    ): BatchIdResponse {
        val safeCount = count.coerceIn(1, 1000)
        val ids = List(safeCount) { ksuidGenerator.nextId() }
        return BatchIdResponse(type = "ksuid", count = safeCount, ids = ids)
    }

    // ── Hashids ────────────────────────────────────────────────────────────

    /**
     * 숫자를 Hashids로 인코딩합니다.
     *
     * [numbers] 쿼리 파라미터에 콤마로 구분된 Long 값을 전달합니다.
     * 예: `/ids/hashids/encode?numbers=1,2,3`
     */
    @GetMapping("/hashids/encode")
    suspend fun hashidsEncode(
        @RequestParam numbers: List<Long>,
    ): HashidsResponse {
        val encoded = hashids.encode(*numbers.toLongArray())
        val decoded = hashids.decode(encoded).toList()
        log.debug { "Hashids encode $numbers → $encoded" }
        return HashidsResponse(numbers = numbers, encoded = encoded, decoded = decoded)
    }

    /**
     * Hashids 문자열을 디코딩합니다.
     *
     * 예: `/ids/hashids/decode/xBNRkG3A`
     */
    @GetMapping("/hashids/decode/{hash}")
    suspend fun hashidsDecode(@PathVariable hash: String): HashidsResponse {
        val decoded = hashids.decode(hash).toList()
        log.debug { "Hashids decode $hash → $decoded" }
        return HashidsResponse(numbers = decoded, encoded = hash, decoded = decoded)
    }
}
