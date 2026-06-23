package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.service.WriteBehindService
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
 * Profile 7 — Write-Behind Cache.
 *
 * Profile 7 — Write-Behind Cache.
 *
 * Measures write throughput when cache updates are synchronous but DB flush is
 * deferred to a background thread ([@Async]) for eventual consistency.
 *
 * Expected: highest write throughput among profiles 6 & 7 (async DB flush),
 * at the cost of eventual consistency.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
class WriteBehindBenchmark : AbstractCacheBenchmark() {
    private lateinit var service: WriteBehindService
    private var counter = 0

    @Setup
    override fun setup() {
        super.setup()
        service = context.getBean(WriteBehindService::class.java)
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
