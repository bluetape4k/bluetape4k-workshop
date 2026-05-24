package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.workshop.cache.benchmark.service.ReadThroughService
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
 * Profile 5 — Read-Through Cache.
 *
 * Manual Redis read-through: cache-first; on miss loads from DB and
 * populates the cache. Similar to Profile 3 but the pattern is explicit
 * and key-prefixed for isolation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
class ReadThroughBenchmark : AbstractCacheBenchmark() {
    private lateinit var service: ReadThroughService

    @Param("1", "100", "500")
    var productId: Long = 1L

    @Setup
    override fun setup() {
        super.setup()
        service = context.getBean(ReadThroughService::class.java)
        repeat(5) { service.findById(productId) }
    }

    @Benchmark
    fun findById() = service.findById(productId)
}
