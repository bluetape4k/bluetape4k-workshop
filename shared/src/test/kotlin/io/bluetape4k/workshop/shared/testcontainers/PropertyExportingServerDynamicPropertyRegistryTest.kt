package io.bluetape4k.workshop.shared.testcontainers

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.PropertyExportingServer
import io.bluetape4k.testcontainers.spring.registerDynamicProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import java.util.function.Supplier

/**
 * [PropertyExportingServer.registerDynamicProperties]의 Spring bridge 계약을
 * Docker 없이 고정합니다.
 *
 * 등록 시 supplier를 평가하지 않는지, 최신 값을 반복해서 읽는지, 예외와 중복
 * 등록을 Spring registry에 그대로 위임하는지, JVM system property를 건드리지
 * 않는지를 검증합니다.
 */
class PropertyExportingServerDynamicPropertyRegistryTest {

    private val systemPropertyKey = "testcontainers.bridge-contract.host"

    @AfterEach
    fun cleanupSystemProperty() {
        System.clearProperty(systemPropertyKey)
    }

    @Test
    fun `propertyKeys 를 namespace 가 포함된 Spring property key 로 등록한다`() {
        val server = FakeServer(
            propertyNamespace = "redis",
            keys = setOf("host", "port", "url"),
            values = mapOf(
                "host" to "localhost",
                "port" to "6379",
                "url" to "redis://localhost:6379",
            ),
        )
        val registry = RecordingRegistry()

        server.registerDynamicProperties(registry)

        registry.names.toSet() shouldBeEqualTo setOf(
            "testcontainers.redis.host",
            "testcontainers.redis.port",
            "testcontainers.redis.url",
        )
        server.propertiesCalls shouldBeEqualTo 0
        registry.value("testcontainers.redis.host") shouldBeEqualTo "localhost"
        registry.value("testcontainers.redis.port") shouldBeEqualTo "6379"
        registry.value("testcontainers.redis.url") shouldBeEqualTo "redis://localhost:6379"
    }

    @Test
    fun `빈 propertyKeys 는 registry 를 변경하지 않는다`() {
        val registry = RecordingRegistry()

        FakeServer(keys = emptySet(), values = emptyMap()).registerDynamicProperties(registry)

        registry.names shouldBeEqualTo emptyList()
    }

    @Test
    fun `supplier 는 등록 시점이 아니라 값 해석 시 properties 를 호출한다`() {
        val server = FakeServer(
            keys = setOf("host"),
            values = mapOf("host" to "before"),
        )
        val registry = RecordingRegistry()

        server.registerDynamicProperties(registry)
        server.propertiesCalls shouldBeEqualTo 0

        server.values = mapOf("host" to "after")
        registry.value("testcontainers.bridge-contract.host") shouldBeEqualTo "after"
        server.propertiesCalls shouldBeEqualTo 1
    }

    @Test
    fun `supplier 는 값을 캐시하지 않고 반복 평가마다 properties 를 호출한다`() {
        val server = FakeServer(
            keys = setOf("host"),
            values = mapOf("host" to "after"),
        )
        val registry = RecordingRegistry()

        server.registerDynamicProperties(registry)

        server.values = mapOf("host" to "latest")
        registry.value("testcontainers.bridge-contract.host") shouldBeEqualTo "latest"
        server.propertiesCalls shouldBeEqualTo 1

        server.values = mapOf("host" to "final")
        registry.value("testcontainers.bridge-contract.host") shouldBeEqualTo "final"
        server.propertiesCalls shouldBeEqualTo 2
    }

    @Test
    fun `propertyKeys 에 선언된 키가 properties 에 없으면 supplier 평가가 실패한다`() {
        val server = FakeServer(
            keys = setOf("host", "port"),
            values = mapOf("host" to "localhost"),
        )
        val registry = RecordingRegistry()

        server.registerDynamicProperties(registry)

        val error = assertFailsWith<IllegalStateException> {
            registry.value("testcontainers.bridge-contract.port")
        }

        error.message shouldBeEqualTo
            "PropertyExportingServer 'bridge-contract' did not provide property 'port'"
    }

    @Test
    fun `properties 예외는 원래 타입과 메시지로 전달된다`() {
        val server = FakeServer(
            keys = setOf("host"),
            values = emptyMap(),
            propertiesFailure = IllegalArgumentException("server is not running"),
        )
        val registry = RecordingRegistry()

        server.registerDynamicProperties(registry)

        val error = assertFailsWith<IllegalArgumentException> {
            registry.value("testcontainers.bridge-contract.host")
        }

        error.message shouldBeEqualTo "server is not running"
    }

    @Test
    fun `등록 전후 시스템 프로퍼티를 변경하지 않는다`() {
        System.setProperty(systemPropertyKey, "existing")
        val registry = RecordingRegistry()

        FakeServer(
            keys = setOf("host"),
            values = mapOf("host" to "replacement"),
        ).registerDynamicProperties(registry)

        System.getProperty(systemPropertyKey) shouldBeEqualTo "existing"
    }

    @Test
    fun `중복 등록은 별도 덮어쓰기 없이 registry 호출에 위임한다`() {
        val server = FakeServer(
            keys = setOf("host"),
            values = mapOf("host" to "localhost"),
        )
        val registry = RecordingRegistry()

        server.registerDynamicProperties(registry)
        server.registerDynamicProperties(registry)

        registry.names shouldBeEqualTo listOf(
            "testcontainers.bridge-contract.host",
            "testcontainers.bridge-contract.host",
        )
        registry.valueAt(0) shouldBeEqualTo "localhost"
        registry.valueAt(1) shouldBeEqualTo "localhost"
    }

    private class FakeServer(
        override val propertyNamespace: String = "bridge-contract",
        private val keys: Set<String>,
        var values: Map<String, String>,
        private val propertiesFailure: RuntimeException? = null,
    ) : PropertyExportingServer {
        var propertiesCalls: Int = 0
            private set

        override fun propertyKeys(): Set<String> = keys

        override fun properties(): Map<String, String> {
            propertiesCalls++
            propertiesFailure?.let { throw it }
            return values
        }
    }

    private class RecordingRegistry : DynamicPropertyRegistry {
        private val entries = mutableListOf<Pair<String, Supplier<Any>>>()

        val names: List<String>
            get() = entries.map { it.first }

        override fun add(name: String, valueSupplier: Supplier<Any>) {
            entries += name to valueSupplier
        }

        fun value(name: String): Any = entries.single { it.first == name }.second.get()

        fun valueAt(index: Int): Any = entries[index].second.get()
    }
}
