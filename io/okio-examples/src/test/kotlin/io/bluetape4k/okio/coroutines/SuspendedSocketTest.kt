package io.bluetape4k.okio.coroutines

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.okio.AbstractOkioTest
import io.bluetape4k.okio.coroutines.internal.await
import io.bluetape4k.support.toUtf8Bytes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.IOException
import okio.Timeout
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.junit5.JUnit5Asserter.fail

class SuspendedSocketTest: AbstractOkioTest() {

    companion object: KLoggingChannel() {
        private const val DEFAULT_TIMEOUT_MS = 2_500L
        private val defaultTimeout = Timeout().timeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `use suspended sink and source for socket`() = runSocketTest { client, server ->
        val clientSource = client.asSuspendedSource().buffered()
        val clientSink = client.asSuspendedSink().buffered()

        val serverSource = server.asSuspendedSource().buffered()
        val serverSink = server.asSuspendedSink().buffered()

        val clientMsg = "동해물과 백두산이"

        clientSink.writeUtf8(clientMsg).flush()
        serverSource.request(clientMsg.toUtf8Bytes().size.toLong())
        serverSource.readUtf8(clientMsg.toUtf8Bytes().size.toLong()) shouldBeEqualTo clientMsg

        val serverMsg = "마르고 닳도록"

        serverSink.writeUtf8(serverMsg).flush()
        clientSource.request(serverMsg.toUtf8Bytes().size.toLong())
        clientSource.readUtf8(serverMsg.toUtf8Bytes().size.toLong()) shouldBeEqualTo serverMsg
    }

    @Test
    fun `read until EOF`() = runSocketTest { client, server ->
        val serverSink = client.asSuspendedSink().buffered()
        val clientSource = server.asSuspendedSource().buffered()

        val message = Fakers.randomString()
        serverSink.writeUtf8(message)
        serverSink.close()

        clientSource.readUtf8() shouldBeEqualTo message
    }

    @Test
    fun `read fails because the socket is already closed`() = runSocketTest { _, server ->
        val serverSource = server.asSuspendedSource().buffered()
        server.close()

        assertFailsWith<IOException> {
            serverSource.readUtf8()
        }
    }

    @Test
    fun `write fails because the socket is already closed`() = runSocketTest { _, server ->
        val serverSink = server.asSuspendedSink().buffered()
        server.close()

        assertFailsWith<IOException> {
            serverSink.writeUtf8(Fakers.randomString())
            serverSink.flush()
        }
    }

    @Test
    fun `blocked read fails due to close`() = runSocketTest { _, server ->
        val serverSource = server.asSuspendedSource().buffered()

        coroutineScope {
            launch {
                delay(500)
                server.close()
            }

            assertFailsWith<IOException> {
                serverSource.request(1L)
            }
        }
    }

    @Test
    fun `blocked write fails due to close`() = runSocketTest { client, server ->
        val clientSink = client.asSuspendedSink().buffered()

        coroutineScope {
            launch {
                delay(500)
                server.close()
            }

            assertFailsWith<IOException> {
                while (true) {
                    clientSink.writeUtf8(Fakers.randomString())
                    clientSink.flush()
                }
            }
        }
    }

    private fun runSocketTest(block: suspend (client: Socket, server: Socket) -> Unit) = runSuspendIO {
        withTimeoutOrNull(defaultTimeout) {
            ServerSocketChannel.open().use { serverSocketChannel ->
                val serverSocket = serverSocketChannel.socket()
                serverSocket.reuseAddress = true
                serverSocketChannel.bind(InetSocketAddress(0))
                serverSocketChannel.configureBlocking(false)

                SocketChannel.open().use { clientChannel ->
                    clientChannel.configureBlocking(false)
                    clientChannel.connect(InetSocketAddress(serverSocket.inetAddress, serverSocket.localPort))
                    clientChannel.await(SelectionKey.OP_CONNECT)
                    if (!clientChannel.finishConnect()) {
                        fail("Failed to finish connecting client channel")
                    }

                    clientChannel.socket().use { client ->
                        serverSocketChannel.await(SelectionKey.OP_ACCEPT)
                        val serverChannel = serverSocketChannel.accept()
                        serverChannel.configureBlocking(false)

                        serverChannel.socket().use { server ->
                            block(client, server)
                        }
                    }

                }
            }
        } ?: fail("Timeout after $DEFAULT_TIMEOUT_MS ms")
    }
}
