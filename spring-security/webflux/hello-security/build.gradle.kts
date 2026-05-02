plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.spring.security.webflux.KotlinWebfluxApplicationKt")
}

// NOTE: implementation 나 runtimeOnly로 지정된 Dependency를 testimplementation 으로도 지정하도록 합니다.
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":shared"))

    testImplementation(libs.bluetape4k.jackson3)
    testImplementation(libs.bluetape4k.junit5)

    // Spring Security
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.thymeleaf)

    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)

    // https://mvnrepository.com/artifact/org.thymeleaf.extras/thymeleaf-extras-springsecurity6
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    compileOnly(libs.reactor.core)
    compileOnly(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)
}
