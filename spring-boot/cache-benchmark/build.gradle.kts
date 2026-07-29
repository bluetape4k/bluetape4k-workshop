plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.cache.benchmark.CacheBenchmarkApplicationKt")
}

allOpen {
    // kotlinx-benchmark 는 @State class 가 open 이어야 합니다.
    annotation("org.openjdk.jmh.annotations.State")
    annotation("kotlinx.benchmark.State")
    // Spring annotation
    annotation("org.springframework.stereotype.Service")
    annotation("org.springframework.stereotype.Component")
    annotation("org.springframework.stereotype.Repository")
}

// Benchmark source set: src/benchmark/kotlin
sourceSets {
    create("benchmark") {
        kotlin {
            srcDir("src/benchmark/kotlin")
        }
        resources {
            srcDir("src/benchmark/resources")
        }
        compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

kotlin {
    target {
        compilations.getByName("benchmark").associateWith(compilations.getByName("main"))
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
    named("benchmarkImplementation") {
        extendsFrom(
            configurations["implementation"],
            configurations["compileOnly"],
            configurations["testImplementation"],
        )
    }
    named("benchmarkRuntimeOnly") {
        extendsFrom(configurations["runtimeOnly"], configurations["testRuntimeOnly"])
    }
}

benchmark {
    targets {
        register("benchmark")
    }
    configurations {
        // 기본 'main' 설정을 비활성화합니다.
        named("main") {
            exclude(".*")
            warmups = 1
            iterations = 1
        }
        // Profile 1: cache 없는 baseline
        register("noCache") {
            include(".*NoCacheBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // Profile 2: Caffeine local cache
        register("caffeine") {
            include(".*CaffeineBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // Profile 3: Redis distributed cache
        register("redis") {
            include(".*RedisCacheBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // Profile 4: Redisson Near Cache (local + Redis)
        register("nearCache") {
            include(".*NearCacheBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // Profile 5: Read-through cache
        register("readThrough") {
            include(".*ReadThroughBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // Profile 6: Write-through cache
        register("writeThrough") {
            include(".*WriteThroughBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // Profile 7: Write-behind cache
        register("writeBehind") {
            include(".*WriteBehindBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // 모든 profile 조합
        register("allProfiles") {
            include(".*Benchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
    }
}

dependencies {
    // Spring Boot Web (benchmark 에 embedded server 가 필요 없는 최소 구성)
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // JPA + H2 (benchmark 용 in-memory DB)
    implementation(libs.spring.boot.starter.data.jpa.lib)
    runtimeOnly(libs.h2.lib)

    // Spring Cache 의존성
    implementation(libs.spring.boot.starter.cache.lib)
    testImplementation(libs.spring.boot.starter.cache.test)

    // Caffeine (local cache)
    implementation(libs.caffeine.lib)
    implementation(libs.caffeine.jcache)

    // Redis (Spring Cache Redis backend)
    implementation(libs.spring.boot.starter.data.redis.lib)
    testImplementation(libs.spring.boot.starter.data.redis.test)

    // Redisson (Near Cache)
    implementation(libs.bluetape4k.redisson)
    implementation(libs.redisson.lib)
    implementation(libs.redisson.spring.boot.starter)

    // bluetape4k utility
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.cache.core)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.testcontainers)

    // 코루틴
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Testcontainers (benchmark 와 test 용 Redis)
    testImplementation(libs.testcontainers.junit.jupiter)

    // 테스트
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.mockk)

    // kotlinx-benchmark runtime (benchmark source set 용)
    add("benchmarkImplementation", libs.kotlinx.benchmark.runtime)
    add("benchmarkImplementation", libs.jmh.core)
}
