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
 * Read path: Redisson map backed by MapLoader-owned DB miss loading.
 * [findByIdHit] measures warmed cache reads. [findByIdMiss] evicts the target
 * key first so the MapLoader path is measured separately.
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
    fun findByIdHit() = service.findById(productId)

    @Benchmark
    fun findByIdMiss() =
        service.evict(productId).let {
            service.findById(productId)
        }
}
