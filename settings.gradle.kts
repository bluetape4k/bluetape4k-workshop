pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        // https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
        id("org.gradle.toolchains.foojay-resolver-convention") version ("0.8.0")
    }
}

val PROJECT_NAME = "bluetape4k"

rootProject.name = "$PROJECT_NAME-workshop"

include("shared")

includeModules("aws", false, false)
includeModules("aws-kotlin", false, true)
includeModules("ddd", false, true)
includeModules("docker", false, false)
includeModules("examples", false, false)
includeModules("exposed", false, false)
includeModules("gateway", false, false)
includeModules("gatling", false, false)
includeModules("graalvm", false, false)

includeModules("haifa", false, true)

includeModules("json", false, false)
includeModules("kotlin", false, true)
includeModules("memory", false, false)
includeModules("mq", false, false)
includeModules("observability", false, false)
includeModules("ratelimit", false, false)

includeModules("spring-boot", false, false)
includeModules("spring-cloud", false, false)
includeModules("spring-data", false, false)
includeModules("spring-security", false, false)
includeModules("vertx", false, false)
includeModules("virtual-thread", false, false)

fun includeModules(baseDir: String, withProjectName: Boolean = true, withBaseDir: Boolean = true) {
    files("$rootDir/$baseDir").files
        .filter { it.isDirectory }
        .forEach { moduleDir ->
            moduleDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { dir ->
                    val basePath = baseDir.replace("/", "-")
                    val projectName = when {
                        !withProjectName && !withBaseDir -> dir.name
                        withProjectName && !withBaseDir  -> PROJECT_NAME + "-" + dir.name
                        withProjectName                  -> PROJECT_NAME + "-" + basePath + "-" + dir.name
                        else                             -> basePath + "-" + dir.name
                    }
                    // println("include modules: $projectName")

                    include(projectName)
                    project(":$projectName").projectDir = dir
                }
        }
}
