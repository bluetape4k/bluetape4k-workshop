package io.bluetape4k.workshop.resilience.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.resilience.controller.BackendAController.Companion.BACKEND_A
import io.bluetape4k.workshop.resilience.service.Service
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/$BACKEND_A")
class BackendAController(@Qualifier("backendAService") private val serviceA: Service) {

    companion object: KLogging() {
        internal const val BACKEND_A = "backendA"
    }

    @GetMapping("failure")
    fun failure() = serviceA.failure()

    @GetMapping("fallback")
    fun failureWithFallback() = serviceA.failureWithFallback()

    @GetMapping("success")
    fun success() = serviceA.success()

    @GetMapping("successException")
    fun successWithException() = serviceA.successWithException()

    @GetMapping("ignore")
    fun ignoreException() = serviceA.ignoreException()

    @GetMapping("fluxSuccess")
    fun fluxSuccess() = serviceA.fluxSuccess()

    @GetMapping("fluxFailure")
    fun fluxFailure() = serviceA.fluxFailure()

    @GetMapping("fluxTimeout")
    fun fluxTimeout() = serviceA.fluxTimeout()

    @GetMapping("monoSuccess")
    fun monoSuccess() = serviceA.monoSuccess()

    @GetMapping("monoFailure")
    fun monoFailure() = serviceA.monoFailure()

    @GetMapping("monoTimeout")
    fun monoTimeout() = serviceA.monoTimeout()

    @GetMapping("futureSuccess")
    fun futureSuccess() = serviceA.futureSuccess()

    @GetMapping("futureFailure")
    fun futureFailure() = serviceA.futureFailure()

    @GetMapping("futureTimeout")
    fun futureTimeout() = serviceA.futureTimeout()

}
