package io.bluetape4k.workshop.idgenerator.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBePositive
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.idgenerator.AbstractIdGeneratorTest
import io.bluetape4k.workshop.idgenerator.model.BatchIdResponse
import io.bluetape4k.workshop.idgenerator.model.HashidsResponse
import io.bluetape4k.workshop.idgenerator.model.SnowflakeResponse
import io.bluetape4k.workshop.idgenerator.model.StringIdResponse
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody

class IdGeneratorControllerTest(context: ApplicationContext) : AbstractIdGeneratorTest(context) {

    companion object : KLogging()

    // ── Snowflake ──────────────────────────────────────────────────────────

    @Test
    fun `snowflake id 생성`() {
        val result = client.get().uri("/ids/snowflake")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<SnowflakeResponse>()

        result.id.shouldBePositive()
        result.timestamp.shouldBeGreaterThan(0L)
        result.machineId.shouldBeGreaterThan(-1)
        result.sequence.shouldBeGreaterThan(-1)
    }

    @Test
    fun `snowflake id 파싱`() {
        // 먼저 ID 생성
        val generated = client.get().uri("/ids/snowflake")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<SnowflakeResponse>()

        // 생성된 ID를 파싱
        val parsed = client.get().uri("/ids/snowflake/parse/${generated.id}")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<SnowflakeResponse>()

        parsed.id shouldBeEqualTo generated.id
        parsed.timestamp shouldBeEqualTo generated.timestamp
        parsed.machineId shouldBeEqualTo generated.machineId
    }

    @Test
    fun `snowflake batch 생성`() {
        val count = 20
        val result = client.get().uri("/ids/snowflake/batch?count=$count")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<BatchIdResponse>()

        result.type shouldBeEqualTo "snowflake"
        result.count shouldBeEqualTo count
        result.ids shouldHaveSize count
        // 모두 유일한 값인지 확인
        result.ids.toSet().size shouldBeEqualTo count
    }

    // ── ULID ───────────────────────────────────────────────────────────────

    @Test
    fun `ulid 생성`() {
        val result = client.get().uri("/ids/ulid")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<StringIdResponse>()

        result.type shouldBeEqualTo "ulid"
        result.id.length shouldBeEqualTo 26  // ULID는 항상 26자
        result.id.shouldNotBeEmpty()
    }

    @Test
    fun `ulid는 단조 증가한다`() {
        val ids = (1..5).map {
            client.get().uri("/ids/ulid")
                .exchange()
                .expectStatus().isOk
                .expectRequiredBody<StringIdResponse>()
                .id
        }
        // ULID는 사전순으로 시간 순서 유지
        ids.zipWithNext().all { (a, b) -> a <= b }.shouldBeTrue()
    }

    @Test
    fun `ulid batch 생성`() {
        val count = 15
        val result = client.get().uri("/ids/ulid/batch?count=$count")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<BatchIdResponse>()

        result.type shouldBeEqualTo "ulid"
        result.count shouldBeEqualTo count
        result.ids shouldHaveSize count
    }

    // ── KSUID ──────────────────────────────────────────────────────────────

    @Test
    fun `ksuid 생성`() {
        val result = client.get().uri("/ids/ksuid")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<StringIdResponse>()

        result.type shouldBeEqualTo "ksuid"
        result.id.length shouldBeEqualTo 27  // KSUID는 항상 27자
        result.id.shouldNotBeEmpty()
    }

    @Test
    fun `ksuid batch 생성`() {
        val count = 10
        val result = client.get().uri("/ids/ksuid/batch?count=$count")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<BatchIdResponse>()

        result.type shouldBeEqualTo "ksuid"
        result.count shouldBeEqualTo count
        result.ids shouldHaveSize count
    }

    // ── Hashids ────────────────────────────────────────────────────────────

    @Test
    fun `hashids 인코딩 및 디코딩`() {
        val numbers = listOf(1L, 2L, 3L)
        val encoded = client.get().uri("/ids/hashids/encode?numbers=1,2,3")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<HashidsResponse>()

        encoded.numbers shouldBeEqualTo numbers
        encoded.encoded.shouldNotBeEmpty()
        encoded.decoded shouldBeEqualTo numbers

        // 디코딩 검증
        val decoded = client.get().uri("/ids/hashids/decode/${encoded.encoded}")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<HashidsResponse>()

        decoded.decoded shouldBeEqualTo numbers
    }

    @Test
    fun `단일 숫자 hashids 인코딩`() {
        val result = client.get().uri("/ids/hashids/encode?numbers=42")
            .exchange()
            .expectStatus().isOk
            .expectRequiredBody<HashidsResponse>()

        result.numbers shouldBeEqualTo listOf(42L)
        result.encoded.length shouldBeGreaterThan 0
        result.decoded shouldBeEqualTo listOf(42L)
    }

    private inline fun <reified T : Any> WebTestClient.ResponseSpec.expectRequiredBody(): T =
        expectBody<T>()
            .returnResult()
            .responseBody
            .shouldNotBeNull()
}
