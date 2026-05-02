plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native) 
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.stomp.websocket.StompWebSocketApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":shared"))
    
    implementation(libs.bluetape4k.core)
    testImplementation(libs.bluetape4k.junit5)

    api(libs.jakarta.annotation.api)

    // Jackson 3
    implementation(libs.jackson3.module.kotlin)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.websocket.lib)
    testImplementation(libs.spring.boot.starter.websocket.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // Websocket
    implementation(libs.webjar.webjars.locator.core)
    implementation(libs.webjar.sockjs.client)
    implementation(libs.webjar.stomp.websocket)
    implementation(libs.webjar.bootstrap)
    implementation(libs.webjar.jquery)
    implementation(libs.webjar.font.awesome)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    implementation(libs.netty.all)
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    implementation(libs.logback.lib)
}
