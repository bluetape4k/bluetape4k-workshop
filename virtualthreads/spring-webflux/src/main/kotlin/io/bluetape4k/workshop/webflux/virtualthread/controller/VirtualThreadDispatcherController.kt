package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.webflux.virtualthread.controller.VirtualThreadDispatcherController.Companion.VIRTUAL_THREAD_PATH
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.Executors

@RestController
@RequestMapping("/$VIRTUAL_THREAD_PATH")
class VirtualThreadDispatcherController: AbstractDispatcherController() {

    companion object: KLoggingChannel() {
        internal const val VIRTUAL_THREAD_PATH = "virtual-thread"
    }

    override val dispatcher: CoroutineDispatcher =
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

    override val path: String = VIRTUAL_THREAD_PATH

}
