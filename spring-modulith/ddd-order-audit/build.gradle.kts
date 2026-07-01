plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.spring.modulith.ddd.audit.DddOrderAuditApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.spring.modulith.starter.jpa)
    testImplementation(libs.spring.modulith.starter.test)

    implementation(libs.jakarta.persistence.api)
    implementation(libs.bluetape4k.hibernate)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.javers.core)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    developmentOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa.lib)
    implementation(libs.spring.boot.starter.validation)
    runtimeOnly(libs.postgresql.driver)

    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.springmockk)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
