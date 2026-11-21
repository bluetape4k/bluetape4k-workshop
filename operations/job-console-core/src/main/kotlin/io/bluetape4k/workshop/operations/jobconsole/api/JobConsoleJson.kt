package io.bluetape4k.workshop.operations.jobconsole.api

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requirePositiveNumber
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Operations Console의 일반 JSON과 strict request JSON 경계를 제공한다. */
/** Operations Console의 일반 JSON과 strict request JSON 경계를 제공한다. */
object JobConsoleJson {
    /** Bluetape 기본 mapper를 공유해 일반 응답과 persistence JSON을 직렬화한다. */
    /** Bluetape 기본 mapper를 공유해 일반 응답과 persistence JSON을 직렬화한다. */
    val defaultMapper: JsonMapper
        get() = Jackson.defaultJsonMapper

    /**
     * Bluetape 기본 mapper의 모듈과 stream factory를 복사한 strict request mapper를 만든다.
     *
     * 요청 경계에 필요한 중복 키, unknown property, trailing token 및 stream 제한은
     * 이 함수에서만 추가해 Ktor와 Spring adapter가 같은 계약을 사용하도록 한다.
     * 예:
     * ```kotlin
     * val mapper = JobConsoleJson.strictRequestMapper(maxRequestBytes = 1_048_576)
     * // 중복 JSON key, 알 수 없는 property, trailing token은 모두 요청 오류가 된다.
     * ```
     */
    fun strictRequestMapper(maxRequestBytes: Int): JsonMapper {
        maxRequestBytes.requirePositiveNumber("maxRequestBytes")

        val baseline = defaultMapper
        val factory = (baseline.tokenStreamFactory() as JsonFactory).rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxDocumentLength(maxRequestBytes.toLong())
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .maxStringLength(maxRequestBytes)
                    .maxNameLength(MAX_NAME_LENGTH)
                    .maxTokenCount(MAX_TOKEN_COUNT)
                    .build(),
            )
            .build()

        return JsonMapper.builder(factory)
            .addModules(baseline.registeredModules())
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            )
            .build()
    }
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private const val MAX_NESTING_DEPTH = 32
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private const val MAX_NAME_LENGTH = 256
    /**
     * `MAX_TOKEN_COUNT`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private const val MAX_TOKEN_COUNT = 256L
}
