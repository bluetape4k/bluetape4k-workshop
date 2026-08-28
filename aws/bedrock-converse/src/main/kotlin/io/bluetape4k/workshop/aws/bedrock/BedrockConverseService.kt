package io.bluetape4k.workshop.aws.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.bluetape4k.aws.kotlin.bedrock.converse
import io.bluetape4k.aws.kotlin.bedrock.converseStreamFlow
import io.bluetape4k.aws.kotlin.bedrock.model.textOrEmpty
import io.bluetape4k.aws.kotlin.bedrock.model.userMessageOf
import io.bluetape4k.aws.kotlin.bedrock.textDeltaFlow
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.useSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

private object BedrockConverseServiceLogging : KLogging()

/**
 * Bedrock Converse의 단일 응답과 스트리밍 응답을 감싸는 consumer 경계입니다.
 *
 * [clientFactory]가 반환한 client는 각 작업 범위가 소유하며 작업이 끝나거나
 * 취소되면 닫힙니다. 스트리밍 결과는 수집할 때만 native SDK를 호출하는
 * cold [Flow]입니다.
 */
class BedrockConverseService(
    private val clientFactory: () -> BedrockRuntimeClient,
) {

    /**
     * 한 번의 Converse 요청을 실행하고 텍스트 블록을 이어 붙여 반환합니다.
     */
    suspend fun converse(prompt: BedrockPrompt): String {
        try {
            BedrockConverseServiceLogging.log.debug { "Bedrock Converse request started." }
            return clientFactory().useSafe { client ->
                client.converse(
                    modelId = prompt.modelId,
                    messages = listOf(userMessageOf(prompt.prompt)),
                ).textOrEmpty()
            }.also {
                BedrockConverseServiceLogging.log.debug { "Bedrock Converse request completed." }
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            BedrockConverseServiceLogging.log.warn(cause) {
                "Bedrock Converse request failed without exposing prompt or response text."
            }
            throw cause
        }
    }

    /**
     * 텍스트 델타만 전달하는 cold Flow를 반환합니다.
     *
     * Flow를 만들 때는 client를 생성하거나 SDK를 호출하지 않습니다. 각 수집은
     * 독립적인 요청과 client 범위를 만들며, collector 취소는 native stream과
     * client 종료로 전파됩니다.
     */
    fun stream(prompt: BedrockPrompt): Flow<String> = flow {
        try {
            BedrockConverseServiceLogging.log.debug { "Bedrock ConverseStream collection started." }
            clientFactory().useSafe { client ->
                emitAll(
                    client.converseStreamFlow(
                        modelId = prompt.modelId,
                        messages = listOf(userMessageOf(prompt.prompt)),
                    ).textDeltaFlow(),
                )
            }
            BedrockConverseServiceLogging.log.debug { "Bedrock ConverseStream collection completed." }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            BedrockConverseServiceLogging.log.warn(cause) {
                "Bedrock ConverseStream failed without exposing prompt or response text."
            }
            throw cause
        }
    }
}
