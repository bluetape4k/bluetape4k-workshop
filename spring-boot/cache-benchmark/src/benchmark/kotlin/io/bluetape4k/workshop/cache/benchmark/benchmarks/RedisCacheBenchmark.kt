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
 * Profile 3 — Redis distributed cache.
 *
 * Uses Spring's [@Cacheable] with a Redis-backed [CacheManager].
 * Expected: lower throughput than Caffeine (network round-trip to Redis) but
 * higher than no-cache baseline; cache is shared across JVM instances.
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
