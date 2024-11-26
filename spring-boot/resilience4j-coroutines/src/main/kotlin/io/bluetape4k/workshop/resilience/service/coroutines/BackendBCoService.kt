package io.bluetape4k.workshop.resilience.service.coroutines

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.resilience.exception.BusinessException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Service("backendBCoService")
class BackendBCoService: CoService {

    companion object: KLogging() {
        private const val BACKEND_B = "backendB"
    }

    override suspend fun suspendFailureWithFallback(): String {
        return runCatching { suspendFailure() }.getOrElse { "This is a fallback" }
    }

    override suspend fun suspendSuccessWithException(): String {
        throw HttpClientErrorException(HttpStatus.BAD_REQUEST, "This is a remote client exception")
    }

    override suspend fun suspendIgnoreException(): String {
        throw BusinessException("이 예외는 backend B에 의해 무시됩니다")
    }

    override suspend fun suspendSuccess(): String {
        return "Hello World from backend B Coroutines"
    }

    override suspend fun suspendFailure(): String {
        throw IOException("BAM!")
    }

    override suspend fun suspendTimeout(): String {
        delay(3.seconds)
        return "Hello World from backend B Coroutines"
    }

    override fun flowSuccess(): Flow<String> {
        return flowOf("Hello", "World")
    }

    override fun flowFailure(): Flow<String> {
        return flowOf("Hello", "World")
            .onStart { throw IOException("BAM!") }
    }

    override fun flowTimeout(): Flow<String> {
        return flowOf("Hello World from backend B Coroutines")
            .onStart { delay(3.seconds) }
    }
}
