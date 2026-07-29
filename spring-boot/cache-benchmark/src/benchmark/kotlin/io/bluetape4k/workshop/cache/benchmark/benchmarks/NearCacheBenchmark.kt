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
 * Profile 4 — Redisson Near Cache 입니다.
 *
 * Redisson 의 [org.redisson.api.RLocalCachedMap] 기반 [NearCacheService] 를 사용합니다.
 * - Tier 1: in-JVM Caffeine 기반 local map (hot 상태에서는 sub-microsecond read)
 * - Tier 2: Redis backing store (invalidation 을 통한 cross-instance consistency)
 *
 * 예상: hot data 에서는 Caffeine 에 가까운 throughput 을 내며, 같은 key 를 반복 read 할 때
 * Redis-only 보다 낫습니다.
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
