plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.spring.security.webflux.jwt.JwtApplicationKt")
}

// NOTE: implementation 나 runtimeOnly로 지정된 Dependency를 testimplementation 으로도 지정하도록 합니다.
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":shared"))

    testImplementation(Libs.bluetape4k_jackson3)
    testImplementation(Libs.bluetape4k_junit5)

    // Spring Security
    implementation(Libs.springBootStarter("security"))
    implementation(Libs.springBootStarter("oauth2-resource-server"))
    testImplementation(Libs.springSecurity("test"))

    implementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    // testImplementation(Libs.okhttp3_mockwebserver)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    implementation(Libs.reactor_core)
    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)
}
