import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    `java-test-fixtures`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

dependencies {
    kover(project(":commerce-usage-billing-meter-service"))
    kover(project(":commerce-usage-billing-usage-service"))
    kover(project(":commerce-usage-billing-billing-service"))
    kover(project(":commerce-usage-billing-invoice-service"))
    kover(project(":commerce-usage-billing-query-service"))

    testImplementation(project(":shared"))
    testImplementation(testFixtures(project(":shared")))
    testImplementation(project(":commerce-usage-billing-meter-service"))
    testImplementation(project(":commerce-usage-billing-usage-service"))
    testImplementation(project(":commerce-usage-billing-billing-service"))
    testImplementation(project(":commerce-usage-billing-invoice-service"))
    testImplementation(project(":commerce-usage-billing-query-service"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.jackson3)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.exposed.spring.boot.jdbc)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.spring.kafka.lib)
    testImplementation(libs.kafka.clients)
    testImplementation(libs.spring.boot.starter.jdbc.lib)
    testImplementation(libs.spring.boot.starter.security)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.jetbrains.exposed.core)
    testImplementation(libs.jetbrains.exposed.dao)
    testImplementation(libs.jetbrains.exposed.jdbc)
    testRuntimeOnly(libs.postgresql.driver)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    description = "Runs usage billing microservice Kafka/PostgreSQL composition tests."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}
