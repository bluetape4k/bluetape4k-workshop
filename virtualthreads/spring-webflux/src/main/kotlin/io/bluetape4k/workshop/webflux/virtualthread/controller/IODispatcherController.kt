package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.webflux.virtualthread.controller.IODispatcherController.Companion.IO_PATH
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/$IO_PATH")
class IODispatcherController: AbstractDispatcherController() {

    companion object: KLoggingChannel() {
        internal const val IO_PATH = "io"
    }

    override val dispatcher: CoroutineDispatcher = Dispatchers.IO

    override val path: String = IO_PATH

}
