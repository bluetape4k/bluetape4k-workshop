plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.leader.zookeeper.LeaderZookeeperAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        // Exclude @Tag("smoke") from the default test run.
        excludeTags("smoke")
    }
}

dependencies {
    // bluetape4k-leader-zookeeper (Apache Curator 5.9.0 transitive)
    implementation(libs.bluetape4k.leader.zookeeper)

    implementation(libs.bluetape4k.logging)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    // Test
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
    // logback-classic for T7 ListAppender<ILoggingEvent> (provided via spring-boot-starter-test transitively)
}
