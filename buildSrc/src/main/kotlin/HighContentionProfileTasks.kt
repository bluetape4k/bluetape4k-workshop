import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

internal data class HighContentionProfileSelection(
    val runId: String,
    val profileId: String,
) {
    companion object {
        private val identifierPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val supportedProfiles = setOf(
            "burst",
            "duplicate-storm",
            "redis-path-outage",
            "redis-key-loss",
            "slow-provider",
            "worker-restart",
            "duplicate-delivery",
        )
        private val reservedProperties = setOf(
            "highContentionContractRoot",
            "highContentionOutputRoot",
            "highContentionRunId",
            "highContentionProfileId",
            "highContentionMode",
            "highContentionImplementation",
            "highContentionExpectedJavaVersion",
            "junit.jupiter.execution.parallel.enabled",
        )

        fun validate(
            runId: String,
            profileId: String,
            reservedSystemProperties: Set<String> = reservedProperties.filterTo(mutableSetOf()) {
                System.getProperty(it) != null
            },
        ): HighContentionProfileSelection {
            require(reservedSystemProperties.isEmpty()) {
                "reserved high-contention system properties must not be supplied by the caller"
            }
            require(identifierPattern.matches(runId)) {
                "highContentionRunId must be a bounded identifier"
            }
            require(profileId in supportedProfiles) {
                "unsupported highContentionProfileId: $profileId"
            }
            return HighContentionProfileSelection(runId, profileId)
        }
    }
}

fun Project.registerHighContentionProfileTask(
    name: String,
    mode: String,
    implementation: String,
): TaskProvider<Test> {
    require(mode == "ci-correctness" || mode == "local-reference") {
        "unsupported high-contention mode: $mode"
    }
    require(implementation in setOf("job-core", "job-spring", "job-ktor", "ticket-spring")) {
        "unsupported high-contention implementation: $implementation"
    }
    val contractRoot = rootProject.layout.projectDirectory.dir("profiles/high-contention/v1")
    val outputRoot = rootProject.layout.buildDirectory.dir("high-contention")
    val runId = providers.gradleProperty("highContentionRunId")
    val profileId = providers.gradleProperty("highContentionProfileId")
    val javaToolchains = extensions.getByType<JavaToolchainService>()

    return tasks.register<Test>(name) {
        description = "Runs the isolated $mode high-contention profile for $implementation."
        group = "verification"
        inputs.dir(contractRoot)
        inputs.property("highContentionRunId", runId)
        inputs.property("highContentionProfileId", profileId)
        outputs.dir(outputRoot)
        outputs.upToDateWhen { false }
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        })
        maxParallelForks = 1
        useJUnitPlatform {
            includeTags("high-contention")
        }
        systemProperty("highContentionContractRoot", contractRoot.asFile.absolutePath)
        systemProperty("highContentionOutputRoot", outputRoot.get().asFile.absolutePath)
        systemProperty("highContentionMode", mode)
        systemProperty("highContentionImplementation", implementation)
        systemProperty("highContentionExpectedJavaVersion", "25")
        systemProperty("junit.jupiter.execution.parallel.enabled", "false")
        rootProject.gradle.sharedServices.registrations.findByName("test-mutex")
            ?.let { usesService(it.service) }

        doFirst {
            val selection = HighContentionProfileSelection.validate(
                runId = required(runId, "highContentionRunId"),
                profileId = required(profileId, "highContentionProfileId"),
            )
            systemProperty("highContentionRunId", selection.runId)
            systemProperty("highContentionProfileId", selection.profileId)
            if (javaLauncher.get().metadata.languageVersion.asInt() != 25) {
                throw GradleException("high-contention profiles require a Java 25 launcher")
            }
        }
    }
}

private fun required(
    provider: Provider<String>,
    name: String,
): String =
    provider.orNull ?: throw GradleException("-P$name=<value> is required")
