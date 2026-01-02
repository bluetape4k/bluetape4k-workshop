plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.cache.redis.RedisCacheApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_redis)
    implementation(Libs.bluetape4k_jackson3)
    testImplementation(Libs.bluetape4k_junit5)
    implementation(Libs.bluetape4k_testcontainers)

    // Codecs
    implementation(Libs.kryo)
    implementation(Libs.fury_kotlin)
    implementation(Libs.fory_kotlin)

    // Compressor
    implementation(Libs.commons_compress)
    implementation(Libs.lz4_java)
    implementation(Libs.snappy_java)
    implementation(Libs.zstd_jni)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    // Lettuce
    implementation(Libs.lettuce_core)
    implementation(Libs.commons_pool2)

    // Netty
    implementation(platform(Libs.netty_bom))
    implementation(Libs.netty_all)
    implementation(Libs.netty_transport_native_epoll)
    implementation(Libs.netty_transport_native_kqueue)

    implementation(Libs.springBootStarter("cache"))
    testImplementation(Libs.springBootStarter("cache-test"))
    implementation(Libs.springBootStarter("data-redis"))
    testImplementation(Libs.springBootStarter("data-redis-test"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
