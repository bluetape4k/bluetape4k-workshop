//plugins {
//    kotlin("plugin.spring")
//    // kotlin("kapt")
//    id(Plugins.spring_boot)
//    id(Plugins.graalvm_native)
//    id(Plugins.gatling) version Plugins.Versions.gatling
//}
//
//springBoot {
//    mainClass.set("io.bluetape4k.workshop.virtualthread.undertow.UndertowVirtualThreadMvcAppKt")
//}
//
//
//configurations {
//    compileOnly.get().extendsFrom(annotationProcessor.get())
//    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
//
//    all {
//        // Undertow 사용을 위해 Tomcat 제거
//        exclude(module = "spring-boot-starter-tomcat")
//    }
//}
//
//
//dependencies {
//    // Bluetape4k
//    implementation(Libs.bluetape4k_io)
//    implementation(Libs.bluetape4k_spring_core)
//    testImplementation(Libs.bluetape4k_spring_tests)
//
//    // Spring Boot
//    implementation(Libs.springBoot("autoconfigure"))
//    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
//    annotationProcessor(Libs.springBoot("configuration-processor"))
//    runtimeOnly(Libs.springBoot("devtools"))
//
//    implementation(Libs.springBootStarter("web"))
//    // Undertow 추가
//    implementation(Libs.springBootStarter("undertow"))
//
//    implementation(Libs.springBootStarter("aspectj"))
//    implementation(Libs.springBootStarter("actuator"))
//    testImplementation(Libs.springBootStarter("test")) {
//        exclude(group = "junit", module = "junit")
//        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
//        exclude(module = "mockito-core")
//    }
//
//    // WebClient 사용을 위해
//    implementation(Libs.springBootStarter("webflux"))
//
//    // Coroutines
//    implementation(Libs.bluetape4k_coroutines)
//    implementation(Libs.kotlinx_coroutines_core)
//    implementation(Libs.kotlinx_coroutines_reactor)
//    testImplementation(Libs.kotlinx_coroutines_test)
//
//    // SpringDoc - OpenAPI 3.0
//    implementation(Libs.springdoc_openapi_starter_webmvc_ui)
//
//    // Gatling
//    implementation(Libs.gatling_app)
//    implementation(Libs.gatling_core_java)
//    implementation(Libs.gatling_http_java)
//    implementation(Libs.gatling_recorder)
//    implementation(Libs.gatling_charts_highcharts)
//    testImplementation(Libs.gatling_test_framework)
//}
