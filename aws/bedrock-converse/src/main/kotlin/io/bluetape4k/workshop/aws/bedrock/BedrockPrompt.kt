package io.bluetape4k.workshop.aws.bedrock

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Bedrock Converse에 전달할 모델 식별자와 사용자 텍스트입니다.
 *
 * 모델별 request 형식을 노출하지 않고 upstream model-neutral helper에
 * 전달할 최소 입력만 보유합니다.
 */
data class BedrockPrompt(
    val modelId: String,
    val prompt: String,
) : Serializable {

    init {
        modelId.requireNotBlank("modelId")
        prompt.requireNotBlank("prompt")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
