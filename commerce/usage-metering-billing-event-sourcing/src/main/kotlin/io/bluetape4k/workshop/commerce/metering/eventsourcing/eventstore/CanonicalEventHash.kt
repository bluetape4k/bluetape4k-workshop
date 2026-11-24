package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.*

/**
 * CanonicalEventHash은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object CanonicalEventHash: KLogging() {
    /**
     * 기술 구현을 위한 내부 상태이며 업무 데이터·비밀값을 외부 응답에 포함하지 않습니다.
     */

    private val mapper: ObjectMapper = Jackson.defaultJsonMapper

    /**
     * sha256은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param material 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun sha256(material: EventHashMaterial): String {
        require(material.streamVersion > 0) { "stream_version_invalid" }
        require(material.schemaVersion > 0) { "schema_version_invalid" }

        val canonical = buildString {
            append("billing-event-v1\n")
            append(material.stream.canonical()).append('\n')
            append(material.streamVersion).append('\n')
            append(material.eventType).append('\n')
            append(material.schemaVersion).append('\n')
            append(canonicalJson(material.payload)).append('\n')
            append(canonicalJson(material.metadata)).append('\n')
            append(material.previousHash.orEmpty())
        }
        return Digesters.SHA256.digestHexUtf8(canonical)
    }

    /**
     * canonicalJson은 사용량 계량·청구 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
     * 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param json 업무 처리에 필요한 json 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun canonicalJson(json: String): String = canonical(mapper.readTree(json))

    /**
     * canonical은 사용량 계량·청구 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
     * 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param node 업무 처리에 필요한 node 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun canonical(node: JsonNode): String =
        when {
            node.isObject -> node.propertyNames().asSequence()
                .sorted()
                .joinToString(",", "{", "}") { name ->
                    "${mapper.writeValueAsString(name)}:${canonical(node.get(name))}"
                }
            node.isArray  -> node.joinToString(",", "[", "]") { canonical(it) }
            node.isString -> mapper.writeValueAsString(node.stringValue())
            node.isNumber -> node.decimalValue().stripTrailingZeros().toPlainString()
            node.isBoolean -> node.booleanValue().toString()
            node.isNull   -> "null"
            else          -> error("unsupported_json_node")
        }
}
