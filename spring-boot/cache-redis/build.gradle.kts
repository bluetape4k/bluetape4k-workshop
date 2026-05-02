plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.cache.redis.RedisCacheApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.spring.boot4.core)
    implementation(libs.bluetape4k.spring.boot4.redis)
    testImplementation(libs.bluetape4k.junit5)
    implementation(libs.bluetape4k.testcontainers)

    // Codecs
    implementation(libs.kryo)
    implementation(libs.fory.kotlin)

    // Compressor
    implementation(libs.commons.compress)
    implementation(libs.lz4.java)
    implementation(libs.snappy.java)
    implementation(libs.zstd.jni)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // Lettuce
    implementation(libs.lettuce.core)
    implementation(libs.commons.pool2)

    // Netty
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.all)
    implementation(libs.netty.transport.native.epoll)
    implementation(libs.netty.transport.native.kqueue)

    implementation(libs.spring.boot.starter.cache.lib)
    testImplementation(libs.spring.boot.starter.cache.test)
    implementation(libs.spring.boot.starter.data.redis.lib)
    testImplementation(libs.spring.boot.starter.data.redis.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
