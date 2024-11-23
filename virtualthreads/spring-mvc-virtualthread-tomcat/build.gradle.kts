plugins {
    kotlin("plugin.spring")
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
    kotlin("kapt")
    id(Plugins.spring_boot)
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
    // NOTE: kapt 에서 빌드가 실패하면 `kapt.use.k2=true` 를 추가해보세요.
    //  # https://kotlinlang.org/docs/kapt.html#try-kotlin-k2-compiler (kotlin 2.0)
    //kapt.use.k2=true

    // showProcessorStats = true
    // kapt 가 제대로 동작하지 않는 경우, 해당 클래스를 약간 수정해보세요. (Comments 추가 등으로)
    // correctErrorTypes = true
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.virtualthread.tomcat.VirtualThreadMvcAppKt")
}

@Suppress("UnstableApiUsage")
configurations {
    compileOnly.get().extendsFrom(annotationProcessor.get())
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Kapt 사용 시 필수적으로 추가해야 함
    api(Libs.jakarta_annotation_api)

    // JPA/Hibernate
    implementation(Libs.bluetape4k_hibernate)
    implementation(Libs.hibernate_core)
    implementation(Libs.hibernate_jcache)
    implementation(Libs.hibernate_validator)
    implementation(Libs.springBootStarter("data-jpa"))

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
    implementation(Libs.bluetape4k_cache)
    implementation(Libs.caffeine)
    implementation(Libs.caffeine_jcache)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("cache"))
    implementation(Libs.springBootStarter("validation"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    // WebClient 사용을 위해
    implementation(Libs.springBootStarter("webflux"))

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
