package io.bluetape4k.workshop.operations.jobconsole.api

import io.bluetape4k.jackson3.Jackson
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Operations Console의 일반 JSON과 strict request JSON 경계를 제공한다. */
object JobConsoleJson {
    /** Bluetape 기본 mapper를 공유해 일반 응답과 persistence JSON을 직렬화한다. */
    val defaultMapper: JsonMapper
        get() = Jackson.defaultJsonMapper

    /**
     * Bluetape 기본 mapper의 모듈과 stream factory를 복사한 strict request mapper를 만든다.
     *
     * 요청 경계에 필요한 중복 키, unknown property, trailing token 및 stream 제한은
     * 이 함수에서만 추가해 Ktor와 Spring adapter가 같은 계약을 사용하도록 한다.
     */
    fun strictRequestMapper(maxRequestBytes: Int): JsonMapper {
        require(maxRequestBytes > 0) { "maxRequestBytes must be positive" }

        val baseline = Jackson.defaultJsonMapper
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

    private const val MAX_NESTING_DEPTH = 32
    private const val MAX_NAME_LENGTH = 256
    private const val MAX_TOKEN_COUNT = 256L
}
