package io.bluetape4k.workshop.resilience.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.resilience.controller.BackendCController.Companion.BACKEND_C
import io.bluetape4k.workshop.resilience.service.Service
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/$BACKEND_C")
class BackendCController(@Qualifier("backendCService") private val serviceC: Service) {

    companion object: KLogging() {
        internal const val BACKEND_C = "backendC"
    }

    @GetMapping("failure")
    fun failure() = serviceC.failure()

    @GetMapping("fallback")
    fun failureWithFallback() = serviceC.failureWithFallback()

    @GetMapping("success")
    fun success() = serviceC.success()

    @GetMapping("successException")
    fun successWithException() = serviceC.successWithException()

    @GetMapping("ignore")
    fun ignoreException() = serviceC.ignoreException()

    @GetMapping("fluxSuccess")
    fun fluxSuccess() = serviceC.fluxSuccess()

    @GetMapping("fluxFailure")
    fun fluxFailure() = serviceC.fluxFailure()

    @GetMapping("fluxTimeout")
    fun fluxTimeout() = serviceC.fluxTimeout()

    @GetMapping("monoSuccess")
    fun monoSuccess() = serviceC.monoSuccess()

    @GetMapping("monoFailure")
    fun monoFailure() = serviceC.monoFailure()

    @GetMapping("monoTimeout")
    fun monoTimeout() = serviceC.monoTimeout()

    @GetMapping("futureSuccess")
    fun futureSuccess() = serviceC.futureSuccess()

    @GetMapping("futureFailure")
    fun futureFailure() = serviceC.futureFailure()

    @GetMapping("futureTimeout")
    fun futureTimeout() = serviceC.futureTimeout()

}
