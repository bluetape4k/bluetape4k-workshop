import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

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
    testFixturesImplementation(libs.bluetape4k.lettuce)
    testFixturesImplementation(libs.bluetape4k.testcontainers)
    testFixturesImplementation(libs.hikaricp)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.testcontainers.toxiproxy)
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
    useJUnitPlatform { excludeTags("integration", "process-probe") }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs container-backed job console integration tests."
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
    implementation = "job-core",
).configure {
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJobConsoleTestRuntime()
}

registerHighContentionProfileTask(
    name = "highContentionLocalReferenceProfile",
    mode = "local-reference",
    implementation = "job-core",
).configure {
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJobConsoleTestRuntime()
}

val highContentionProcessProbeChild = tasks.register<Test>("highContentionProcessProbeChild") {
    description = "Internal one-shot test-worker process probe."
    notCompatibleWithConfigurationCache("The task consumes a one-shot parent-issued process descriptor.")
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform { includeTags("process-probe") }
    maxParallelForks = 1
    forkEvery = 1
    jvmArgs("-Xshare:off", "--enable-preview")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")

    doFirst {
        val descriptorValue = providers.gradleProperty("highContentionProbeDescriptor").orNull
            ?: throw GradleException("highContentionProcessProbeChild requires a one-shot descriptor")
        val descriptor = Path.of(descriptorValue).toAbsolutePath().normalize()
        val configuredProbeBase = rootProject.layout.buildDirectory
            .dir("high-contention/process-probe")
            .get()
            .asFile
            .toPath()
            .toAbsolutePath()
            .normalize()
        if (!descriptor.startsWith(configuredProbeBase) || Files.isSymbolicLink(descriptor)) {
            throw GradleException("process probe descriptor must remain beneath the trusted probe root")
        }
        if (!Files.isRegularFile(descriptor, NOFOLLOW_LINKS) || Files.size(descriptor) !in 1..4_096) {
            throw GradleException("process probe descriptor is missing or outside its bounded size")
        }
        val probeBase = configuredProbeBase.toRealPath(NOFOLLOW_LINKS)
        val realDescriptor = descriptor.toRealPath(NOFOLLOW_LINKS)
        if (!realDescriptor.startsWith(probeBase)) {
            throw GradleException("process probe descriptor escaped the trusted probe root")
        }
        val fields = Files.readAllLines(realDescriptor)
            .filter(String::isNotBlank)
            .map { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    throw GradleException("process probe descriptor contains an invalid field")
                }
                line.substring(0, separator) to line.substring(separator + 1)
            }
        if (fields.map(Pair<String, String>::first).distinct().size != fields.size) {
            throw GradleException("process probe descriptor contains duplicate fields")
        }
        val descriptorFields = fields.toMap()
        if (
            descriptorFields.keys != setOf(
                "schemaVersion",
                "probeId",
                "probeRoot",
                "deadlineEpochMillis",
            ) ||
            descriptorFields.getValue("schemaVersion") != "1" ||
            !Regex("[0-9a-f]{32}").matches(descriptorFields.getValue("probeId"))
        ) {
            throw GradleException("process probe descriptor schema is invalid")
        }
        val processOwner = providers.systemProperty("highContentionProcessOwner").orNull
            ?: throw GradleException("process probe owner token is missing")
        if (processOwner != descriptorFields.getValue("probeId")) {
            throw GradleException("process probe owner token does not match its descriptor")
        }
        val probeRoot = Path.of(descriptorFields.getValue("probeRoot")).toRealPath(NOFOLLOW_LINKS)
        if (probeRoot != realDescriptor.parent.toRealPath(NOFOLLOW_LINKS) || !probeRoot.startsWith(probeBase)) {
            throw GradleException("process probe root does not match its descriptor")
        }
        val deadlineEpochMillis = descriptorFields.getValue("deadlineEpochMillis").toLongOrNull()
            ?: throw GradleException("process probe deadline is invalid")
        val nowEpochMillis = System.currentTimeMillis()
        if (deadlineEpochMillis <= nowEpochMillis || deadlineEpochMillis > nowEpochMillis + 60_000) {
            throw GradleException("process probe descriptor is expired")
        }
        Files.delete(realDescriptor)
        FileChannel.open(realDescriptor.parent, READ).use { channel ->
            try {
                channel.force(true)
            } catch (_: UnsupportedOperationException) {
                // Directory fsync is not supported by every file provider.
            }
        }
        systemProperty("highContentionProbeRoot", probeRoot.toString())
        systemProperty("highContentionProbeDeadlineEpochMillis", deadlineEpochMillis.toString())
        systemProperty("highContentionProcessOwner", processOwner)
    }
}

tasks.register<HighContentionProcessProbeTask>("highContentionProcessProbe") {
    description = "Proves that a timed-out nested Gradle test worker and its descendant are completely reaped."
    group = "verification"
    repositoryRoot.set(rootProject.layout.projectDirectory)
    gradleWrapper.set(rootProject.layout.projectDirectory.file("gradlew"))
    childTaskPath.set(":operations-job-console-core:highContentionProcessProbeChild")
    probeBaseDirectory.set(rootProject.layout.buildDirectory.dir("high-contention/process-probe"))
}
