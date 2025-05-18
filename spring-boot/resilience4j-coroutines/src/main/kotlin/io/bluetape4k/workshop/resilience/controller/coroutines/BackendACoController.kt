package io.bluetape4k.workshop.resilience.controller.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.resilience.service.coroutines.CoService
import kotlinx.coroutines.flow.Flow
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/coroutines/backendA")
class BackendACoController(
    @Qualifier("backendACoService") private val serviceA: CoService,
) {
    companion object: KLoggingChannel() {
        private const val BACKEND_A = "backendA"
    }

    @GetMapping("suspendSuccess")
    suspend fun suspendSuccess() = serviceA.suspendSuccess()

    @GetMapping("suspendFailure")
    suspend fun suspendFailure(): String = serviceA.suspendFailure()

    @GetMapping("suspendFallback")
    suspend fun suspendFallback(): String = serviceA.suspendFailureWithFallback()

    @GetMapping("suspendTimeout")
    suspend fun suspendTimeout(): String = serviceA.suspendTimeout()

    @GetMapping("flowSuccess")
    fun flowSuccess(): Flow<String> = serviceA.flowSuccess()

    @GetMapping("flowFailure")
    fun flowFailure(): Flow<String> = serviceA.flowFailure()

    @GetMapping("flowTimeout")
    fun flowTimeout(): Flow<String> = serviceA.flowTimeout()

}
