import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-test-fixtures`
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

configurations.configureEach {
    exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.core)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.jetbrains.exposed.java.time)
    implementation(libs.hikaricp)
    implementation(libs.micrometer.core)
    runtimeOnly(libs.postgresql.driver)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.exposed.jdbc.tests)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.mockk)

    testFixturesImplementation(libs.bluetape4k.assertions)
    testFixturesImplementation(libs.bluetape4k.jackson3)
    testFixturesImplementation(libs.bluetape4k.testcontainers)
    testFixturesImplementation(libs.testcontainers.postgresql)
}

fun Test.useJobConsoleTestRuntime() {
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    jvmArgs(
        "-Xshare:off",
        "-Xms2G",
        "-Xmx4G",
        "-XX:+UseZGC",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+EnableDynamicAgentLoading",
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Didea.io.use.nio2=true",
    )
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("management.datadog.metrics.export.enabled", "false")
    environment("DD_API_KEY", providers.environmentVariable("DD_API_KEY").orElse("test-api-key").get())
    environment(
        "DD_APPLICATION_KEY",
        providers.environmentVariable("DD_APPLICATION_KEY").orElse("test-application-key").get(),
    )
    testLogging {
        events("failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.test {
    useJobConsoleTestRuntime()
    inputs.dir(rootProject.layout.projectDirectory.dir("profiles/high-contention/v1"))
    systemProperty(
        "highContentionContractRoot",
        rootProject.layout.projectDirectory.dir("profiles/high-contention/v1").asFile.absolutePath,
    )
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs container-backed job console integration tests."
    group = "verification"
    useJobConsoleTestRuntime()
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}
