plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.leader.LeaderElectionAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        // 기본 test 실행에서 @Tag("smoke")를 제외합니다.
        // Smoke test(T5 LeaseExpiryTest, T6 RedisFailureTest)는 timing-dependent라
        // nightly 또는 manual 실행에만 적합합니다.
        excludeTags("smoke")
    }
}

dependencies {
    // bluetape4k-leader - distributed leader election(독립 groupId: io.github.bluetape4k.leader)
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.leader.redis.lettuce)

    // Redis / Lettuce
    implementation(libs.lettuce.core)
    implementation(libs.bluetape4k.logging)

    // Spring Boot - sibling module pattern을 따릅니다(autoconfigure.lib + starter.actuator, bare starter key는 catalog에 없음).
    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    // 테스트
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.coroutines)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.mockk)
}
