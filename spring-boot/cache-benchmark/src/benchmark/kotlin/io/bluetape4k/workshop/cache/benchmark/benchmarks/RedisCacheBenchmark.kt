package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.workshop.cache.benchmark.service.RedisCacheService
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import java.util.concurrent.TimeUnit

/**
 * Profile 3 — Redis distributed cache 입니다.
 *
 * Redis 기반 [CacheManager] 와 Spring [@Cacheable] 을 사용합니다.
 * 예상: Redis network round-trip 때문에 Caffeine 보다 throughput 은 낮지만 no-cache baseline 보다 높고,
 * cache 는 JVM instance 사이에서 공유됩니다.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
class RedisCacheBenchmark : AbstractCacheBenchmark() {
    private lateinit var service: RedisCacheService

    @Param("1", "100", "500")
    var productId: Long = 1L

    @Setup
    override fun setup() {
        super.setup()
        service = context.getBean(RedisCacheService::class.java)
        repeat(5) { service.findById(productId) }
    }

    @Benchmark
    fun findById() = service.findById(productId)
}
