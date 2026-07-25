plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("highContentionRoot") {
            id = "io.bluetape4k.workshop.high-contention-root"
            implementationClass =
                "io.bluetape4k.workshop.buildlogic.highcontention.HighContentionRootPlugin"
        }
        create("highContentionProfile") {
            id = "io.bluetape4k.workshop.high-contention-profile"
            implementationClass =
                "io.bluetape4k.workshop.buildlogic.highcontention.HighContentionProfilePlugin"
        }
    }
}

kotlin {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }
}

tasks.test {
    useJUnitPlatform()
}
