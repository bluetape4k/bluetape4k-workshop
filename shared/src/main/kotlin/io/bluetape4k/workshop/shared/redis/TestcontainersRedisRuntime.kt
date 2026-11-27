package io.bluetape4k.workshop.shared.redis

import io.bluetape4k.testcontainers.storage.RedisServer

/** upstream RedisServer를 명시적 lifecycle으로 감싸는 Testcontainers 실행기입니다. */
class TestcontainersRedisRuntime(
    image: String = RedisServer.IMAGE,
    tag: String = RedisServer.TAG,
    useDefaultPort: Boolean = false,
    reuse: Boolean = false,
): RedisRuntime {

    private val delegate = OwnedRedisRuntime {
        TestcontainersRedisHandle(
            RedisServer(
                image = image,
                tag = tag,
                useDefaultPort = useDefaultPort,
                reuse = reuse,
            ),
        )
    }

    override val isRunning: Boolean get() = delegate.isRunning
    override val isClosed: Boolean get() = delegate.isClosed
    override fun start(): RedisEndpoint = delegate.start()
    override fun close() = delegate.close()

    private class TestcontainersRedisHandle(
        private val server: RedisServer,
    ): RedisContainerHandle {
        override fun start() = server.start()
        override fun stop() = server.stop()
        override fun endpoint(): RedisEndpoint = RedisEndpoint(server.host, server.port, server.url)
    }
}
