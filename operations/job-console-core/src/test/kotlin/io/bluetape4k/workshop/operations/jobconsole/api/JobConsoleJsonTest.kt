package io.bluetape4k.workshop.operations.jobconsole.api

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature

/** Jackson 설정이 request strictness와 redacted wire JSON 경계를 보존하는지 검증한다. */
/** Jackson 설정이 request strictness와 redacted wire JSON 경계를 보존하는지 검증한다. */
class JobConsoleJsonTest {

    private companion object: KLogging() {
/**
 * MAX_REQUEST_BYTES은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val MAX_REQUEST_BYTES = 64 * 1024
    }

    @Test
/**
 * 이 시나리오는 "strict request mapper keeps the bluetape baseline and request boundary" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `strict request mapper keeps the bluetape baseline and request boundary`() {
        val mapper = JobConsoleJson.strictRequestMapper(MAX_REQUEST_BYTES)

        mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).shouldBeTrue()
        mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).shouldBeTrue()
        mapper.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).shouldBeTrue()
        mapper.tokenStreamFactory().isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION).shouldBeTrue()
        mapper.tokenStreamFactory().streamReadConstraints().maxDocumentLength shouldBeEqualTo MAX_REQUEST_BYTES.toLong()
        mapper.tokenStreamFactory().streamReadConstraints().maxNestingDepth shouldBeEqualTo 32
        mapper.tokenStreamFactory().streamReadConstraints().maxStringLength shouldBeEqualTo MAX_REQUEST_BYTES
        mapper.tokenStreamFactory().streamReadConstraints().maxNameLength shouldBeEqualTo 256
        mapper.tokenStreamFactory().streamReadConstraints().maxTokenCount shouldBeEqualTo 256L
    }
}
