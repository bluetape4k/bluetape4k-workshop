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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Smoke tests are timing-sensitive (lease expiry) — exclude from default CI run.
// To include: ./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags=
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("smoke")
    }
}

dependencies {
    // Logging
    implementation(libs.bluetape4k.logging)

    // Redisson (bluetape4k-redisson uses bluetape4k-idgenerators for getLockId; add explicitly)
    implementation(libs.bluetape4k.redis)
    implementation(libs.bluetape4k.redisson)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.redisson.lib)
    implementation(libs.redisson.spring.boot.starter)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)

    // Tests
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
