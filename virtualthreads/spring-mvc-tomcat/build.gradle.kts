plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.gatling.plugin)
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
    includeCompileClasspath = true
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
    // api(libs.jakarta.annotation.api)

    implementation(libs.bluetape4k.core)
    // VirtualThread of JDK 21
    implementation(libs.bluetape4k.virtualthread.api)
    runtimeOnly(libs.bluetape4k.virtualthread.jdk21)

    implementation(libs.bluetape4k.testcontainers)

    // JPA/Hibernate
    implementation(libs.bluetape4k.hibernate)
    implementation(libs.hibernate.core)
    implementation(libs.hibernate.jcache)
    implementation(libs.hibernate.validator.lib)
    implementation(libs.spring.boot.starter.data.jpa.lib)
    testImplementation(libs.spring.boot.starter.data.jpa.test)

    api(libs.jakarta.persistence.api)
    api(libs.hibernate.core)

    // QueryDsl
    implementation(libs.querydsl.jpa)
    kapt(libs.querydsl.apt.get().toString() + ":jakarta")
    kaptTest(libs.querydsl.apt.get().toString() + ":jakarta")

    // Database
    implementation(libs.hikaricp)

    // H2
    implementation(libs.h2.v2)

    // MySQL
    implementation(libs.mysql.connector.j)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.mysql)

    // Cache
    implementation(libs.bluetape4k.cache.core)
    implementation(libs.caffeine.lib)
    implementation(libs.caffeine.jcache)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.cache.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    // WebClient 사용을 위해
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // SpringDoc - OpenAPI 3.0
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // Gatling
    implementation(libs.gatling.app)
    implementation(libs.gatling.core.java)
    implementation(libs.gatling.http.java)
    implementation(libs.gatling.recorder)
    implementation(libs.gatling.charts.highcharts)
    testImplementation(libs.gatling.test.framework)
}
