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
import java.util.concurrent.TimeUnit

/**
 * Profile 6 — Write-Through Cache.
 *
 * Measures stable-ID updates through Redisson WRITE_THROUGH. Each [save]
 * returns only after MapWriter persistence is complete.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
class WriteThroughBenchmark : AbstractCacheBenchmark() {
    private lateinit var service: WriteThroughService
    private lateinit var targetProducts: List<Product>
    private var counter = 0

    @Setup
    override fun setup() {
        super.setup()
        service = context.getBean(WriteThroughService::class.java)
        targetProducts = (1..100).map { id ->
            requireNotNull(service.findById(id.toLong())) { "Missing benchmark product id=$id" }
        }
    }

    @Benchmark
    fun findByIdHit() = service.findById(1L)

    @Benchmark
    fun updateExisting(): Product {
        val revision = counter++
        val product = targetProducts[revision % targetProducts.size]
        return service.save(
            product.copy(
                name = "${product.name}-wt-$revision",
                stock = product.stock + revision,
            )
        )
    }
}
