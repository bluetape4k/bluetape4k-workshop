package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.workshop.cache.benchmark.service.CaffeineService
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
 * Profile 2 — Caffeine local cache 입니다.
 *
 * Caffeine 기반 [CacheManager] 와 Spring [@Cacheable] 을 사용합니다.
 * 예상: warm-up 이후 Profile 1 보다 훨씬 높은 throughput 을 냅니다.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
class CaffeineBenchmark : AbstractCacheBenchmark() {
    private lateinit var service: CaffeineService

    @Param("1", "100", "500")
    var productId: Long = 1L

    @Setup
    override fun setup() {
        super.setup()
        service = context.getBean(CaffeineService::class.java)
        // cache 를 warm-up 합니다.
        repeat(10) { service.findById(productId) }
    }

    @Benchmark
    fun findById() = service.findById(productId)
}
