import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.concurrent.atomic.AtomicLong

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "22"
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.commerce.voucherpool.PreGeneratedVoucherPoolApplicationKt")
}

kover {
    reports {
        total {
            xml {
                onCheck = false
            }
        }
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

configurations.configureEach {
    exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
}

dependencies {
    testImplementation(project(":shared"))

    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.virtualthread.api)
    runtimeOnly(libs.bluetape4k.virtualthread.jdk25)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.spring.boot.jdbc)
    implementation(libs.jetbrains.exposed.java.time)
    implementation(libs.jetbrains.exposed.spring7.transaction)
    testImplementation(libs.exposed.jdbc.tests) {
        exclude(group = "org.jetbrains.exposed", module = "exposed-spring-boot4-starter")
    }

    implementation(libs.bluetape4k.bucket4j)
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.lettuce)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.leader.micrometer)
    implementation(libs.bluetape4k.leader.redis.lettuce)

    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.mockk)
}

fun Test.failOnZeroTests() {
    val executed = AtomicLong()
    addTestListener(
        object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) = Unit

            override fun afterSuite(
                suite: TestDescriptor,
                result: TestResult,
            ) = Unit

            override fun beforeTest(testDescriptor: TestDescriptor) = Unit

            override fun afterTest(
                testDescriptor: TestDescriptor,
                result: TestResult,
            ) {
                executed.incrementAndGet()
            }
        },
    )
    doFirst { executed.set(0L) }
    doLast {
        check(executed.get() > 0L) { "$name discovered zero tests" }
    }
}

fun Test.verifyNonEmptyXmlResults() {
    reports.junitXml.required.set(true)
    doLast {
        val xmlFiles =
            reports.junitXml.outputLocation.get().asFile
                .walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .toList()
        check(xmlFiles.isNotEmpty() && xmlFiles.all { it.length() > 0L }) {
            "$name did not produce non-empty XML test results"
        }
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("stress", "migration-compatibility")
    }
}

val migrationCompatibilityTest = tasks.register<Test>("migrationCompatibilityTest") {
    description = "Runs voucher pool migration compatibility tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("migration-compatibility")
    }
    failOnZeroTests()
    verifyNonEmptyXmlResults()
    shouldRunAfter(tasks.test)
}

val stressTest = tasks.register<Test>("stressTest") {
    description = "Runs voucher pool stress tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stress")
    }
    failOnZeroTests()
    verifyNonEmptyXmlResults()
    shouldRunAfter(tasks.test)
}
