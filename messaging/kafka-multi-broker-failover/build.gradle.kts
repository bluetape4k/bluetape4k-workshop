import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set(
        "io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaMultiBrokerFailoverApplicationKt"
    )
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(testFixtures(project(":shared")))
    implementation(libs.bluetape4k.kafka4)
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.logging)
    implementation(libs.kafka.clients)
    implementation(libs.spring.kafka.lib)
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.testcontainers.kafka)
}

tasks.withType<Test>().configureEach {
    providers.environmentVariable("KAFKA_FAILOVER_RUN_ID").orNull?.let { runId ->
        systemProperty("KAFKA_FAILOVER_RUN_ID", runId)
    }
}
