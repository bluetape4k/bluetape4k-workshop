import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.workshop.operations.jobconsole.ktor.JobConsoleKtorApplicationKt")
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
    implementation(platform(libs.ktor.bom))
    implementation(project(":operations-job-console-core"))
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)

    testImplementation(testFixtures(project(":operations-job-console-core")))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.lettuce)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.mockk)
}

fun Test.useJobConsoleTestRuntime() {
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    jvmArgs(
        "-Xshare:off", "-Xms2G", "-Xmx4G", "-XX:+UseZGC",
        "-XX:+UnlockExperimentalVMOptions", "-XX:+EnableDynamicAgentLoading",
        "--enable-preview", "--enable-native-access=ALL-UNNAMED", "-Didea.io.use.nio2=true",
    )
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("management.datadog.metrics.export.enabled", "false")
    environment("DD_API_KEY", providers.environmentVariable("DD_API_KEY").orElse("test-api-key").get())
    environment("DD_APPLICATION_KEY", providers.environmentVariable("DD_APPLICATION_KEY").orElse("test-application-key").get())
    testLogging { events("failed"); showExceptions = true; showCauses = true; showStackTraces = true }
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

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs live Ktor job console integration tests."
    group = "verification"
    useJobConsoleTestRuntime()
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform {
        includeTags("integration")
        excludeTags("high-contention")
    }
    shouldRunAfter(tasks.test)
}

registerHighContentionProfileTask(
    name = "highContentionCiProfile",
    mode = "ci-correctness",
    implementation = "job-ktor",
).configure {
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJobConsoleTestRuntime()
}

registerHighContentionProfileTask(
    name = "highContentionLocalReferenceProfile",
    mode = "local-reference",
    implementation = "job-ktor",
).configure {
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJobConsoleTestRuntime()
}
