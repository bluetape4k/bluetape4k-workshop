plugins {
    kotlin("plugin.spring")
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
    kotlin("kapt")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
    id(Plugins.gatling) version Plugins.Versions.gatling
}

// JPA Entities 들을 Java와 같이 모두 override 가능하게 합니다 (Kotlin 은 기본이 final 입니다)
// 이렇게 해야 association의 proxy 가 만들어집니다.
// https://kotlinlang.org/docs/reference/compiler-plugins.html
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}

kapt {
    correctErrorTypes = true
    showProcessorStats = true

    arguments {
        arg("querydsl.entityAccessors", "true")  // Association의 property는 getter/setter를 사용하도록 합니다.
        arg("querydsl.kotlinCodegen", "true") // QueryDSL Kotlin Codegen 활성화
    }
    javacOptions {
        option("--add-modules", "java.base")
        option("--enable-preview")             // for Java 21 Virtual Threads
    }
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.virtualthread.tomcat.VirtualThreadMvcAppKt")
}


configurations {
    compileOnly.get().extendsFrom(annotationProcessor.get())
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Kapt 사용 시 필수적으로 추가해야 함
    // api(Libs.jakarta_annotation_api)

    implementation(Libs.bluetape4k_core)
    // VirtualThread of JDK 25
    implementation(Libs.bluetape4k_virtualthread_api)
    runtimeOnly(Libs.bluetape4k_virtualthread_jdk25)

    // JPA/Hibernate
    implementation(Libs.bluetape4k_hibernate)
    implementation(Libs.hibernate_core)
    implementation(Libs.hibernate_jcache)
    implementation(Libs.hibernate_validator)
    implementation(Libs.springBootStarter("data-jpa"))
    testImplementation(Libs.springBootStarter("data-jpa-test"))

    api(Libs.jakarta_persistence_api)
    api(Libs.hibernate_core)

    // QueryDsl
    implementation(Libs.querydsl_jpa + ":jakarta")
    kapt(Libs.querydsl_apt + ":jakarta")
    kaptTest(Libs.querydsl_apt + ":jakarta")

    // Database
    implementation(Libs.hikaricp)

    // H2
    implementation(Libs.h2_v2)

    // MySQL
    implementation(Libs.mysql_connector_j)
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers_mysql)

    // Cache
    implementation(Libs.bluetape4k_cache_local)
    implementation(Libs.caffeine)
    implementation(Libs.caffeine_jcache)

    // Spring
    implementation(Libs.bluetape4k_spring_core)
    testImplementation(Libs.bluetape4k_spring_tests)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aspectj"))
    implementation(Libs.springBootStarter("cache"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("web"))
    testImplementation(Libs.springBootStarter("webmvc-test"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    // WebClient 사용을 위해
    implementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // SpringDoc - OpenAPI 3.0
    implementation(Libs.springdoc_openapi_starter_webmvc_ui)

    // Gatling
    implementation(Libs.gatling_app)
    implementation(Libs.gatling_core_java)
    implementation(Libs.gatling_http_java)
    implementation(Libs.gatling_recorder)
    implementation(Libs.gatling_charts_highcharts)
    testImplementation(Libs.gatling_test_framework)
}
