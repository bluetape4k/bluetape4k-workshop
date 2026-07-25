package io.bluetape4k.workshop.buildlogic.highcontention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

class HighContentionRootPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.registerHighContentionSuiteTask(
            taskName = "highContentionCi",
            suiteMode = "ci-correctness",
        )
        project.registerHighContentionSuiteTask(
            taskName = "highContentionLocalReference",
            suiteMode = "local-reference",
        )
    }

    private fun Project.registerHighContentionSuiteTask(
        taskName: String,
        suiteMode: String,
    ) {
        val prefixedProjectProperties =
            providers.gradlePropertiesPrefixedBy("highContention")
        val prefixedDottedSystemProperties =
            providers.systemPropertiesPrefixedBy("high.contention.")
        val prefixedCamelSystemProperties =
            providers.systemPropertiesPrefixedBy("highContention")
        val prefixedPublicEnvironment =
            providers.environmentVariablesPrefixedBy("HIGH_CONTENTION_")
        val prefixedInternalEnvironment =
            providers.environmentVariablesPrefixedBy("BLUETAPE_HIGH_CONTENTION_")

        tasks.register<HighContentionSuiteTask>(taskName) {
            description = "Runs the $suiteMode high-contention matrix sequentially."
            group = "verification"
            repositoryRoot.set(layout.projectDirectory)
            contractRoot.set(layout.projectDirectory.dir("profiles/high-contention/v1"))
            gradleWrapper.set(layout.projectDirectory.file("gradlew"))
            validationScript.set(
                layout.projectDirectory.file("scripts/high-contention/validate-run.mjs"),
            )
            runId.set(providers.gradleProperty("highContentionRunId").orElse(""))
            mode.set(suiteMode)
            profileId.set(providers.gradleProperty("highContentionProfileId"))
            implementation.set(providers.gradleProperty("highContentionImplementation"))
            outputRoot.set(layout.buildDirectory.dir("reports/high-contention"))
            uploadRoot.set(layout.buildDirectory.dir("reports/high-contention-upload"))
            coordinatorWorkRoot.set(layout.buildDirectory.dir("high-contention-coordinator"))

            doFirst {
                HighContentionRootCallerBoundary.validate(
                    projectPropertyNames = prefixedProjectProperties.get().keys,
                    systemPropertyNames =
                        prefixedDottedSystemProperties.get().keys +
                            prefixedCamelSystemProperties.get().keys,
                    environmentNames =
                        prefixedPublicEnvironment.get().keys +
                            prefixedInternalEnvironment.get().keys,
                )
                HighContentionBootstrapFailure.prepare(
                    uploadRoot = uploadRoot.get().asFile.toPath(),
                    runId = runId.get(),
                )
            }
        }
    }
}

class HighContentionProfilePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.registerHighContentionProfileTask("highContentionCiProfile")
        project.registerHighContentionProfileTask("highContentionLocalReferenceProfile")

        if (project.path == ":operations-job-console-core") {
            project.tasks.register<HighContentionProcessProbeTask>("highContentionProcessProbe") {
                description =
                    "Proves that a timed-out nested Gradle test worker and its descendant are completely reaped."
                group = "verification"
                repositoryRoot.set(project.rootProject.layout.projectDirectory)
                gradleWrapper.set(project.rootProject.layout.projectDirectory.file("gradlew"))
                childTaskPath.set(":operations-job-console-core:highContentionProcessProbeChild")
                probeBaseDirectory.set(
                    project.rootProject.layout.buildDirectory.dir("high-contention/process-probe"),
                )
            }
        }
    }
}
