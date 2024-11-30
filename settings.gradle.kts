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

includeModules("aws", false, true)
includeModules("ddd", false, true)
includeModules("docker", false, true)
includeModules("examples", false, false)
includeModules("exposed", false, true)
includeModules("gateway", false, false)
includeModules("gatling", false, true)
includeModules("graalvm", false, false)
includeModules("json", false, false)
includeModules("kotlin", false, true)
includeModules("mq", false, false)
includeModules("observability", false, false)
includeModules("ratelimit", false, false)
includeModules("reactive", false, true)
includeModules("redis", false, true)

includeModules("spring-boot", false, true)
includeModules("spring-cloud", false, true)
includeModules("spring-data", false, true)
includeModules("spring-security", false, true)
includeModules("vertx", false, true)
includeModules("virtualthreads", false, true)

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
