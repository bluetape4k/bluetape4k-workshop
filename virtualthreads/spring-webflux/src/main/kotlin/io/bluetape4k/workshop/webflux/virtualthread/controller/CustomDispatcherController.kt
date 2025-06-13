package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.workshop.webflux.virtualthread.controller.CustomDispatcherController.Companion.CUSTOM_PATH
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.newFixedThreadPoolContext
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient

@RestController
@RequestMapping("/$CUSTOM_PATH")
class CustomDispatcherController(
    override val webClientBuilder: WebClient.Builder,
): AbstractDispatcherController(webClientBuilder) {

    companion object: KLoggingChannel() {
        internal const val CUSTOM_PATH = "custom"
    }

    override val dispatcher: CoroutineDispatcher = newFixedThreadPoolContext(
        2 * Runtimex.availableProcessors,
        "custom"
    )


    override val path: String = CUSTOM_PATH

}
