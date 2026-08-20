plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.optimization.fieldservice.FieldServiceDispatchApplicationKt")
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
    implementation(libs.bluetape4k.http)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.virtualthread.api)
    runtimeOnly(libs.bluetape4k.virtualthread.jdk25)
    implementation(libs.httpclient5)
    implementation(libs.httpcore5.lib)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.jackson3)
    implementation("org.jetbrains.exposed:exposed-java-time")
    implementation("org.jetbrains.exposed:exposed-spring-boot4-starter")
    implementation("org.jetbrains.exposed:spring7-transaction")
    testImplementation(libs.exposed.jdbc.tests) {
        exclude(group = "org.jetbrains.exposed", module = "exposed-spring-boot4-starter")
    }

    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)

    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    developmentOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
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
    testImplementation(libs.wiremock)
}
