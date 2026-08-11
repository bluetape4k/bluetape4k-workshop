plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.lock.DistributedLockAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

// smoke test 는 lease expiry 때문에 timing-sensitive 하므로 기본 CI 실행에서 제외합니다.
// 포함해서 실행하려면 ./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags= 를 사용합니다.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("smoke")
    }
}

dependencies {
    // logging 의존성입니다.
    implementation(libs.bluetape4k.logging)

    // Redisson 의존성입니다. bluetape4k-redisson 은 getLockId 에 bluetape4k-idgenerators 를 사용하므로 명시적으로 추가합니다.
    implementation(libs.bluetape4k.redis)
    implementation(libs.bluetape4k.redisson)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.redisson.lib)
    implementation(libs.redisson.spring.boot.starter)

    // coroutine 의존성입니다.
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)

    // Spring Boot 의존성입니다.
    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)

    // test 의존성입니다.
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
