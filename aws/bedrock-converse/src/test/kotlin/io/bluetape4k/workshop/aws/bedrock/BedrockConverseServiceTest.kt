package io.bluetape4k.workshop.aws.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDeltaEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamResponse
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BedrockConverseServiceTest {

    private val client = mockk<BedrockRuntimeClient>(relaxed = true)
    private lateinit var service: BedrockConverseService

    @BeforeEach
    fun setUp() {
        clearMocks(client)
        service = BedrockConverseService(clientFactory = { client })
    }

    @Test
    fun `converse maps each model and prompt into a native request`() = runTest {
        val capturedRequests = mutableListOf<aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest>()
        coEvery { client.converse(any()) } returns responseOf("hello")

        listOf("anthropic.claude-3-haiku", "amazon.nova-lite").forEach { modelId ->
            service.converse(BedrockPrompt(modelId = modelId, prompt = "Say hello")) shouldBeEqualTo "hello"
            coVerify { client.converse(capture(capturedRequests)) }
            val request = capturedRequests.last()
            request.modelId shouldBeEqualTo modelId
            request.messages.orEmpty().single().content.orEmpty().single() shouldBeEqualTo
                ContentBlock.Text("Say hello")
        }
    }

    @Test
    fun `stream is cold and invokes the native stream once per collection`() = runTest {
        val response = ConverseStreamResponse { stream = flowOfText("a", "b") }
        coEvery {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        } coAnswers {
            secondArg<suspend (ConverseStreamResponse) -> Unit>()(response)
        }

        val stream = service.stream(BedrockPrompt("amazon.nova-lite", "Say hello"))
        coVerify(exactly = 0) {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        }

        stream.toList() shouldBeEqualTo listOf("a", "b")
        stream.toList() shouldBeEqualTo listOf("a", "b")

        coVerify(exactly = 2) {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        }
    }

    @Test
    fun `stream cancellation closes the client after native stream cleanup`() = runTest {
        val events = mutableListOf<String>()
        val response = ConverseStreamResponse {
            stream = flow {
                try {
                    awaitCancellation()
                } finally {
                    events += "stream-finally"
                }
            }
        }
        coEvery {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        } coAnswers {
            secondArg<suspend (ConverseStreamResponse) -> Unit>()(response)
        }
        every { client.close() } answers { events += "client-close" }

        val job = launch {
            service.stream(BedrockPrompt("anthropic.claude-3-haiku", "Say hello")).collect { }
        }
        runCurrent()
        job.cancelAndJoin()
        events += "caller-cancelled"

        events shouldBeEqualTo listOf("stream-finally", "client-close", "caller-cancelled")
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `native failure identity reaches the caller unchanged`() = runTest {
        val expected = IllegalStateException("bedrock unavailable")
        coEvery { client.converse(any()) } throws expected

        val actual = assertFailsWith<IllegalStateException> {
            service.converse(BedrockPrompt("amazon.nova-lite", "Say hello"))
        }

        actual shouldBeSameInstanceAs expected
    }

    @Test
    fun `blank model and prompt are rejected before client use`() = runTest {
        coEvery { client.converse(any()) } returns responseOf("unused")

        assertFailsWith<IllegalArgumentException> {
            service.converse(BedrockPrompt(modelId = " ", prompt = "hello"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.converse(BedrockPrompt(modelId = "amazon.nova-lite", prompt = " "))
        }

        coVerify(exactly = 0) { client.converse(any()) }
    }

    private fun responseOf(text: String): ConverseResponse {
        val response = mockk<ConverseResponse>()
        val message = mockk<Message>()
        every { response.output } returns ConverseOutput.Message(message)
        every { message.content } returns listOf(ContentBlock.Text(text))
        return response
    }

    private fun flowOfText(vararg values: String) = flow {
        values.forEach { emit(textDelta(it)) }
    }

    private fun textDelta(text: String): ConverseStreamOutput =
        ConverseStreamOutput.ContentBlockDelta(
            ContentBlockDeltaEvent {
                contentBlockIndex = 0
                delta = ContentBlockDelta.Text(text)
            },
        )
}
