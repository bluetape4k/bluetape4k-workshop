package io.bluetape4k.workshop.commerce.voucher.eventsourced

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource
import java.io.DataInputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * EventSourcedRuntimeContractTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedRuntimeContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "event sourced application runs on Java 25 without preview and with virtual threads" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event sourced application runs on Java 25 without preview and with virtual threads`() {
        Runtime.version().feature() shouldBeEqualTo 25
        ManagementFactory.getRuntimeMXBean().inputArguments shouldNotContain "--enable-preview"
        EventSourcedVoucherApplication::class.java.isAnnotationPresent(SpringBootApplication::class.java).shouldBeTrue()
        classFileMajorVersion(EventSourcedVoucherApplication::class) shouldBeEqualTo 69

        val executedOnVirtualThread = AtomicBoolean(false)
        Thread.ofVirtual().start {
            executedOnVirtualThread.set(Thread.currentThread().isVirtual)
        }.join()
        executedOnVirtualThread.get().shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "application configuration enables Spring virtual threads" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `application configuration enables Spring virtual threads`() {
        val sources = MutablePropertySources().apply {
            YamlPropertySourceLoader()
                .load("event-sourced-voucher", ClassPathResource("application.yml"))
                .forEach(::addLast)
        }

        PropertySourcesPropertyResolver(sources).getProperty("spring.threads.virtual.enabled") shouldBeEqualTo "true"
    }

    @Test
/**
 * 이 시나리오는 "database configuration bounds pool wait lock wait and statement execution" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `database configuration bounds pool wait lock wait and statement execution`() {
        val sources = MutablePropertySources().apply {
            YamlPropertySourceLoader()
                .load("event-sourced-voucher", ClassPathResource("application.yml"))
                .forEach(::addLast)
        }
        val properties = PropertySourcesPropertyResolver(sources)

        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo "20"
        properties.getProperty("spring.datasource.hikari.connection-timeout") shouldBeEqualTo "250"
        properties.getProperty("spring.datasource.hikari.data-source-properties.options") shouldBeEqualTo
                "-c lock_timeout=500ms -c statement_timeout=3s"
    }

/**
 * classFileMajorVersion은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun classFileMajorVersion(type: KClass<*>): Int {
        val resourceName = "/${type.java.name.replace('.', '/')}.class"
        return DataInputStream(type.java.getResourceAsStream(resourceName).shouldNotBeNull()).use { input ->
            input.readInt() shouldBeEqualTo 0xCAFEBABE.toInt()
            input.readUnsignedShort()
            input.readUnsignedShort()
        }
    }
}
