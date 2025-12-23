plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native) 
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.stomp.websocket.StompWebSocketApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_core)
    testImplementation(Libs.bluetape4k_junit5)

    api(Libs.jakarta_annotation_api)

    // Jackson 3
    implementation(Libs.jackson3_module_kotlin)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("websocket"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // Websocket
    implementation(Libs.webjar("webjars-locator-core", "0.52"))
    implementation(Libs.webjar("sockjs-client", "1.5.1"))
    implementation(Libs.webjar("stomp-websocket", "2.3.4"))
    implementation(Libs.webjar("bootstrap", "5.2.3"))
    implementation(Libs.webjar("jquery", "3.6.4"))
    implementation(Libs.webjar("font-awesome", "6.4.0"))

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_jdk8)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    implementation(Libs.netty_all)
    implementation(Libs.reactor_netty)
    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    implementation(Libs.logback)
}
