package io.bluetape4k.workshop.shared.redis

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 시작/종료 책임을 호출자가 소유하는 Redis endpoint입니다. */
data class RedisEndpoint(
    val host: String,
    val port: Int,
    val url: String,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be between 1 and 65535" }
        require(url.isNotBlank()) { "url must not be blank" }
    }

    /** 기존 Testcontainers property bridge와 호환되는 연결 속성입니다. */
    fun properties(namespace: String = "redis"): Map<String, String> {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        return linkedMapOf(
            "testcontainers.$namespace.host" to host,
            "testcontainers.$namespace.port" to port.toString(),
            "testcontainers.$namespace.url" to url,
        )
    }
}

/** Testcontainers 또는 다른 Redis 실행기를 감싸는 최소 lifecycle SPI입니다. */
interface RedisContainerHandle {
    fun start()
    fun stop()
    fun endpoint(): RedisEndpoint
}

/** Redis 실행기의 명시적 소유권과 partial-start rollback을 정의합니다. */
interface RedisRuntime: AutoCloseable {
    fun start(): RedisEndpoint

    val isRunning: Boolean
    val isClosed: Boolean
}

/**
 * Redis handle을 한 번 시작하고, 종료 시 한 번만 정리하는 lifecycle 구현입니다.
 *
 * 생성자는 실행하지 않으며, 시작 실패 시 생성된 handle을 즉시 역순 정리합니다.
 */
class OwnedRedisRuntime(
    private val factory: () -> RedisContainerHandle,
): RedisRuntime {

    private enum class State { NEW, STARTED, CLOSED }

    private val lock = ReentrantLock()
    private var state: State = State.NEW
    private var activeHandle: RedisContainerHandle? = null
    private var activeEndpoint: RedisEndpoint? = null

    override val isRunning: Boolean
        get() = lock.withLock { state == State.STARTED }

    override val isClosed: Boolean
        get() = lock.withLock { state == State.CLOSED }

    override fun start(): RedisEndpoint = lock.withLock {
        when (state) {
            State.STARTED -> return@withLock requireNotNull(activeEndpoint)
            State.CLOSED -> error("Redis runtime is already closed")
            State.NEW -> Unit
        }

        val handle = try {
            factory()
        } catch (failure: Throwable) {
            state = State.CLOSED
            throw failure
        }

        try {
            handle.start()
            val endpoint = handle.endpoint()
            activeHandle = handle
            activeEndpoint = endpoint
            state = State.STARTED
            endpoint
        } catch (failure: Throwable) {
            state = State.CLOSED
            rollback(handle, failure)
            throw failure
        }
    }

    /** 현재 endpoint를 시스템 프로퍼티로 등록하고 이전 값을 복원하는 handle을 반환합니다. */
    fun registerSystemProperties(namespace: String = "redis"): AutoCloseable {
        val properties = start().properties(namespace)
        val previous = properties.mapValues { (key, _) -> System.getProperty(key) }
        properties.forEach { (key, value) -> System.setProperty(key, value) }
        return AutoCloseable {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    override fun close() {
        val handle = lock.withLock {
            when (state) {
                State.NEW -> {
                    state = State.CLOSED
                    null
                }

                State.STARTED -> {
                    state = State.CLOSED
                    activeHandle.also {
                        activeHandle = null
                        activeEndpoint = null
                    }
                }

                State.CLOSED -> null
            }
        } ?: return

        try {
            handle.stop()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    private fun rollback(handle: RedisContainerHandle, failure: Throwable) {
        try {
            handle.stop()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            failure.addSuppressed(interrupted)
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
    }
}
