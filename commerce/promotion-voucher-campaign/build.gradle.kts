import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
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
    mainClass.set("io.bluetape4k.workshop.commerce.voucher.VoucherCampaignApplicationKt")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
    implementation(libs.exposed.jackson3)
    implementation(libs.exposed.spring.boot.jdbc)
    implementation(libs.exposed.spring.modulith)
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

    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.events.jackson)
    implementation(libs.spring.modulith.actuator)
    implementation(libs.spring.modulith.observability)
    testImplementation(libs.spring.modulith.starter.test)

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

val compatibility = sourceSets.create("compatibility") {
    java.srcDir("src/compatibility/java")
    compileClasspath = files()
    runtimeClasspath = output
}

val previousBinaryJar = tasks.register<Jar>("previousBinaryJar") {
    archiveClassifier.set("previous-binary")
    from(compatibility.output)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

fun Test.useWorkshopTestRuntime() {
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
    useWorkshopTestRuntime()
    useJUnitPlatform {
        excludeTags("stress", "migration-compatibility")
    }
}

val stressRun = providers.gradleProperty("voucherStressRun").orNull
val missingStressRun = "missing-voucher-stress-run"
val resolvedStressRun = stressRun ?: missingStressRun
val stressReportDirectory = layout.buildDirectory.dir("reports/voucher-stress/$resolvedStressRun").get().asFile.absolutePath
val stressTest = tasks.register<Test>("stressTest") {
    description = "Runs voucher stress evidence profiles."
    group = "verification"
    useWorkshopTestRuntime()
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform {
        includeTags("stress")
    }
    outputs.dir(stressReportDirectory)
    systemProperty("voucher.stress.run", resolvedStressRun)
    systemProperty("voucher.stress.report-directory", stressReportDirectory)
    doFirst {
        check(systemProperties["voucher.stress.run"] != "missing-voucher-stress-run") {
            "-PvoucherStressRun=<unique-run-id> is required"
        }
    }
    shouldRunAfter(tasks.test)
}

val migrationCompatibilityTest = tasks.register<Test>("migrationCompatibilityTest") {
    description = "Runs packaged voucher migration compatibility processes."
    group = "verification"
    useWorkshopTestRuntime()
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform {
        includeTags("migration-compatibility")
    }
    dependsOn(tasks.bootJar, previousBinaryJar)
    val artifactsDirectory = layout.buildDirectory.dir("libs").get().asFile
    val postgresqlDriver =
        configurations.testRuntimeClasspath
            .get()
            .files
            .single { it.name.startsWith("postgresql-") && it.extension == "jar" }
    systemProperty(
        "voucher.compatibility.current-boot-jar",
        artifactsDirectory.resolve("${project.name}.jar").absolutePath,
    )
    systemProperty(
        "voucher.compatibility.previous-jar",
        artifactsDirectory.resolve("${project.name}-previous-binary.jar").absolutePath,
    )
    systemProperty("voucher.compatibility.postgresql-driver", postgresqlDriver.absolutePath)
    shouldRunAfter(tasks.test)
}
