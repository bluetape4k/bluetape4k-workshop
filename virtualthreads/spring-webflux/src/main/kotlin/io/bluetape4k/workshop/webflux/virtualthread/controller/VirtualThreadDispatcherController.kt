package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.webflux.virtualthread.controller.VirtualThreadDispatcherController.Companion.VIRTUAL_THREAD_PATH
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.Executors

@RestController
@RequestMapping("/$VIRTUAL_THREAD_PATH")
class VirtualThreadDispatcherController(
    override val webClientBuilder: WebClient.Builder,
): AbstractDispatcherController(webClientBuilder) {

    companion object: KLogging() {
        internal const val VIRTUAL_THREAD_PATH = "virtual-thread"
    }

    override val dispatcher: CoroutineDispatcher =
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

    override val path: String = VIRTUAL_THREAD_PATH

}
