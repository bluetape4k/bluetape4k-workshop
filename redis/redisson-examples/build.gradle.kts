plugins {
    alias(libs.plugins.kotlin.spring)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    testImplementation(project(":shared"))

    // Redis 의존성입니다.
    implementation(libs.bluetape4k.redis)
    implementation(libs.bluetape4k.redisson)
    testImplementation(libs.bluetape4k.testcontainers)

    // Redisson 의존성입니다.
    implementation(libs.redisson.lib)
    implementation(libs.redisson.spring.boot.starter)

    // Lettuce 의존성입니다.
    implementation(libs.lettuce.core)

    // Jackson 직렬화 의존성입니다.
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.dataformat.protobuf)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // gRPC 의존성입니다.
    implementation(libs.bluetape4k.grpc)

    // codec 의존성입니다.
    implementation(libs.fory.kotlin)
    implementation(libs.kryo)

    // compressor 의존성입니다.
    implementation(libs.lz4.java)
    implementation(libs.snappy.java)
    implementation(libs.zstd.jni)

    // Protobuf 의존성입니다.
    implementation(libs.protobuf.java.lib)
    implementation(libs.protobuf.java.util)
    implementation(libs.protobuf.kotlin)

    // cache 의존성입니다.
    implementation(libs.bluetape4k.cache.core)
    implementation(libs.caffeine.lib)
    implementation(libs.caffeine.jcache)

    // coroutine 의존성입니다.
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // IdGenerators
    implementation(libs.bluetape4k.idgenerators)

    // Redis 의존성입니다.son Map Read/Write Through 예제를 위해
    testImplementation(libs.bluetape4k.jdbc)
    testRuntimeOnly(libs.h2.lib)
    testImplementation(libs.hikaricp)
    testImplementation(libs.spring.boot.starter.jdbc.lib)

    testImplementation(libs.spring.boot.starter.data.redis.lib)
    testImplementation(libs.spring.boot.starter.data.redis.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
