package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.service.WriteThroughService
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * Profile 6 — Write-Through Cache.
 *
 * Measures write throughput when both the Redis cache and the DB are updated
 * synchronously on every [save] call.
 *
 * Also measures read throughput (cache-warmed) to compare with write cost.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
class WriteThroughBenchmark : AbstractCacheBenchmark() {
    private lateinit var service: WriteThroughService
    private var counter = 0

    @Setup
    override fun setup() {
        super.setup()
        service = context.getBean(WriteThroughService::class.java)
        // Pre-populate
        repeat(5) { service.findById(1L) }
    }

    @Benchmark
    fun findById() = service.findById(1L)

    @Benchmark
    fun saveAndRead(): Product {
        val product = Product(
            name = "BenchProduct-${counter++}",
            category = "Benchmark",
            price = BigDecimal("9.99"),
            stock = 10,
        )
        return service.save(product)
    }
}
