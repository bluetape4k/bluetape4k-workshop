plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.leader.k8slease.K8sLeaseMicrometerAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        excludeTags("k8s")
    }
}

val k8sTest = tasks.register<Test>("k8sTest") {
    description = "Runs opt-in Kubernetes-backed Lease integration tests when added."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform {
        includeTags("k8s")
    }
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.leader.k8s)
    implementation(libs.bluetape4k.leader.micrometer)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.fabric8.kubernetes.client)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)

    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webmvc.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.micrometer.observation.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.mockk)
}
