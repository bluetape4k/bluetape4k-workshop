plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.spring.security.webflux.KotlinWebfluxApplicationKt")
}

// NOTE: implementation 나 runtimeOnly로 지정된 Dependency를 testimplementation 으로도 지정하도록 합니다.
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.bluetape4k_jackson3)
    testImplementation(Libs.bluetape4k_junit5)

    // Spring Security
    implementation(Libs.springBootStarter("security"))
    implementation(Libs.springBootStarter("thymeleaf"))

    implementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))

    // https://mvnrepository.com/artifact/org.thymeleaf.extras/thymeleaf-extras-springsecurity6
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    testImplementation(Libs.springSecurity("test"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    api(Libs.kotlinx_coroutines_reactor)
    api(Libs.kotlinx_coroutines_core)
    api(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    compileOnly(Libs.reactor_core)
    compileOnly(Libs.reactor_kotlin_extensions)
    compileOnly(Libs.reactor_test)
}
