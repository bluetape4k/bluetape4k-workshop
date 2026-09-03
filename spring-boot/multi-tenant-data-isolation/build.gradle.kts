plugins {
    alias(libs.plugins.exposed)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.multitenant"
        databaseUrl = "jdbc:h2:mem:spring-boot-multi-tenant-data-isolation-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}
springBoot {
    mainClass.set("io.bluetape4k.workshop.multitenant.MultiTenantDataIsolationApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)

    // Bluetape4k 의존성
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.tenant)
    implementation(libs.bluetape4k.tenant.reactor)
    implementation(libs.bluetape4k.spring.boot4.core)
    implementation(libs.bluetape4k.micrometer)

    // Exposed 의존성
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)
    implementation(libs.jetbrains.exposed.spring7.transaction)

    // DB 의존성
    implementation(libs.hikaricp)
    runtimeOnly(libs.h2.v2)

    // Spring Boot 의존성
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    developmentOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Observability 의존성
    implementation(libs.micrometer.core)
    testImplementation(libs.reactor.test)
}
