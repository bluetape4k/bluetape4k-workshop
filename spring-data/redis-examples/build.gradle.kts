plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.redis.RedisApplicationKt")
    buildInfo()
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_redis)
    implementation(Libs.bluetape4k_spring_core)
    implementation(Libs.bluetape4k_jackson)
    implementation(Libs.bluetape4k_idgenerators)
    testImplementation(Libs.bluetape4k_junit5)
    implementation(Libs.bluetape4k_testcontainers)

    // spring-data-redis에서는 기본적으로 lettuce를 사용합니다.
    // Redisson
    // implementation(Libs.redisson)
    // https://github.com/redisson/redisson/blob/master/redisson-spring-data/README.md
    // spring-data-redis 2.7.x 를 사용하므로, redisson도 같은 버전을 참조해야 한다
    // implementation(Libs.redisson_spring_data_27)

    // Codecs
    implementation(Libs.kryo)
    implementation(Libs.fury_kotlin)

    // Compressor
    implementation(Libs.lz4_java)
    implementation(Libs.snappy_java)
    implementation(Libs.zstd_jni)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactive)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    implementation(Libs.lettuce_core)
    implementation(Libs.commons_pool2)
    implementation(Libs.springBootStarter("data-redis"))

    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(group = "org.mockito", module = "mockito-core")
    }

}
