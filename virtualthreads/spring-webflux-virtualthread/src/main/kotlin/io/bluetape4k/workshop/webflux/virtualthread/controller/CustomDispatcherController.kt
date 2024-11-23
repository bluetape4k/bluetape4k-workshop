package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.webflux.virtualthread.controller.CustomDispatcherController.Companion.CUSTOM_PATH
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.Executors

@RestController
@RequestMapping("/$CUSTOM_PATH")
class CustomDispatcherController(
    override val webClientBuilder: WebClient.Builder,
): AbstractDispatcherController(webClientBuilder) {

    companion object: KLogging() {
        internal const val CUSTOM_PATH = "custom"
    }

    private val execuctor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    override val dispatcher: CoroutineDispatcher = execuctor.asCoroutineDispatcher()


    override val path: String = CUSTOM_PATH

}
