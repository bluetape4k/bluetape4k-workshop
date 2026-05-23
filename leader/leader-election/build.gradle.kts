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
        // Exclude @Tag("smoke") from the default test run.
        // Smoke tests (T5 LeaseExpiryTest, T6 RedisFailureTest) are timing-dependent
        // and suitable only for nightly or manual runs.
        excludeTags("smoke")
    }
}

dependencies {
    // bluetape4k-leader — distributed leader election (independent groupId: io.github.bluetape4k.leader)
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.leader.redis.lettuce)

    // Redis / Lettuce
    implementation(libs.lettuce.core)
    implementation(libs.bluetape4k.logging)

    // Spring Boot — follows sibling module pattern (autoconfigure.lib + starter.actuator; bare starter key absent from catalog)
    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    // Test
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.mockk)
}
