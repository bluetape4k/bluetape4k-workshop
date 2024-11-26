package io.bluetape4k.workshop.resilience.service.coroutines

import kotlinx.coroutines.flow.Flow

interface CoService {

    suspend fun suspendFailureWithFallback(): String
    suspend fun suspendSuccessWithException(): String
    suspend fun suspendIgnoreException(): String

    suspend fun suspendSuccess(): String
    suspend fun suspendFailure(): String
    suspend fun suspendTimeout(): String

    fun flowSuccess(): Flow<String>
    fun flowFailure(): Flow<String>
    fun flowTimeout(): Flow<String>
}
