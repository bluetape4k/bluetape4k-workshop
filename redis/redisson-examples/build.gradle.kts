plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    testImplementation(project(":shared"))

    // Redis
    implementation(Libs.bluetape4k_redis)
    testImplementation(Libs.bluetape4k_testcontainers)

    // Redisson
    implementation(Libs.redisson)
    implementation(Libs.redisson_spring_boot_starter)

    // Lettuce
    implementation(Libs.lettuce_core)

    // Jackson
    implementation(Libs.bluetape4k_jackson3)
    implementation(Libs.jackson3_dataformat_protobuf)
    implementation(Libs.jackson3_module_kotlin)
    implementation(Libs.jackson3_module_blackbird)

    // Grpc
    implementation(Libs.bluetape4k_grpc)

    // Codecs
    implementation(Libs.fory_kotlin)
    implementation(Libs.kryo)

    // Compressor
    implementation(Libs.lz4_java)
    implementation(Libs.snappy_java)
    implementation(Libs.zstd_jni)

    // Protobuf
    implementation(Libs.protobuf_java)
    implementation(Libs.protobuf_java_util)
    implementation(Libs.protobuf_kotlin)

    // Cache
    implementation(Libs.bluetape4k_cache_local)
    implementation(Libs.caffeine)
    implementation(Libs.caffeine_jcache)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // IdGenerators
    implementation(Libs.bluetape4k_idgenerators)

    // Redisson Map Read/Write Through 예제를 위해
    testImplementation(Libs.bluetape4k_jdbc)
    testRuntimeOnly(Libs.h2)
    testImplementation(Libs.hikaricp)
    testImplementation(Libs.springBootStarter("jdbc"))

    testImplementation(Libs.springBootStarter("data-redis"))
    testImplementation(Libs.springBootStarter("data-redis-test"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
