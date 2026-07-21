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
    mainClass.set("io.bluetape4k.workshop.commerce.ticket.TicketFlashSaleApplicationKt")
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
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.spring.boot.jdbc)
    implementation(libs.exposed.spring.modulith)

    implementation(libs.bluetape4k.lettuce)
    implementation(libs.bluetape4k.bucket4j)
    implementation(libs.bucket4j.lettuce)
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.leader.micrometer)
    implementation(libs.bluetape4k.leader.redis.lettuce)

    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    runtimeOnly(libs.postgresql.driver)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    setJvmArgs((jvmArgs ?: emptyList()).filterNot { it == "--enable-preview" })
    useJUnitPlatform {
        excludeTags("stress")
    }
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
}
