package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.webflux.virtualthread.controller.DefaultDispatcherController.Companion.DEFAULT_PATH
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/$DEFAULT_PATH")
class DefaultDispatcherController: AbstractDispatcherController() {

    companion object: KLoggingChannel() {
        internal const val DEFAULT_PATH = "default"
    }

    override val dispatcher: CoroutineDispatcher = Dispatchers.Default

    override val path: String = DEFAULT_PATH

}
