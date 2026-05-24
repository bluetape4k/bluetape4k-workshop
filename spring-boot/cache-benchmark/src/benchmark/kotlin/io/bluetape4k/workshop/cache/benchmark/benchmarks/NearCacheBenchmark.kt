package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.workshop.cache.benchmark.service.NearCacheService
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
 * Profile 4 — Redisson Near Cache.
 *
 * Uses [NearCacheService] backed by Redisson's [org.redisson.api.RLocalCachedMap]:
 * - Tier 1: in-JVM Caffeine-backed local map (sub-microsecond reads when hot)
 * - Tier 2: Redis backing store (cross-instance consistency via invalidation)
 *
 * Expected: close to Caffeine throughput for hot data; better than Redis-only for
 * repeated reads of the same key.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
class NearCacheBenchmark : AbstractCacheBenchmark() {
    private lateinit var service: NearCacheService

    @Param("1", "100", "500")
    var productId: Long = 1L

    @Setup
    override fun setup() {
        super.setup()
        service = context.getBean(NearCacheService::class.java)
        repeat(10) { service.findById(productId) }  // populate local tier
    }

    @Benchmark
    fun findById() = service.findById(productId)
}
