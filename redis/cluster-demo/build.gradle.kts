plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.redis.cluster.RedisClusterApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    testImplementation(project(":shared"))

    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.idgenerators)
    testImplementation(libs.bluetape4k.junit5)
    implementation(libs.bluetape4k.testcontainers)

    // spring-data-redis에서는 기본적으로 lettuce를 사용합니다.
    // Redisson
    // implementation(libs.redisson.lib)
    // https://github.com/redisson/redisson/blob/master/redisson-spring-data/README.md
    // spring-data-redis 2.7.x 를 사용하므로, redisson도 같은 버전을 참조해야 한다
    // implementation(libs.redisson.spring.data.27)

    // Codecs
    implementation(libs.fory.kotlin)
    implementation(libs.kryo5)

    // Compressor
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

    implementation(libs.lettuce.core)
    implementation(libs.commons.pool2)
    implementation(libs.spring.boot.starter.data.redis.lib)

    implementation(libs.spring.boot.autoconfigure.lib)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(group = "org.mockito", module = "mockito-core")
    }
}
