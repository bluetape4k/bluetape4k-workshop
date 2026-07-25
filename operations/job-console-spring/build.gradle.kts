import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("io.bluetape4k.workshop.high-contention-profile")
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
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

springBoot {
    mainClass.set("io.bluetape4k.workshop.operations.jobconsole.spring.JobConsoleSpringApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

configurations.configureEach {
    exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
}

dependencies {
    implementation(project(":operations-job-console-core"))
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.virtualthread.api)
    runtimeOnly(libs.bluetape4k.virtualthread.jdk25)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql.driver)

    testImplementation(testFixtures(project(":operations-job-console-core")))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.lettuce)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.spring.boot.starter.webflux.test)
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
    useJUnitPlatform {
        excludeTags("integration")
        excludeTags("high-contention")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs live Spring job console integration tests."
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

tasks.named<Test>("highContentionCiProfile") {
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJobConsoleTestRuntime()
}

tasks.named<Test>("highContentionLocalReferenceProfile") {
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJobConsoleTestRuntime()
}
