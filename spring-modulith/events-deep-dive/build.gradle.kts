plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.kapt)
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
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    // Spring Modulith
    implementation(libs.spring.modulith.starter.jpa)
    testImplementation(libs.spring.modulith.starter.test)

    api(libs.jakarta.annotation.api)
    api(libs.jakarta.persistence.api)
    api(libs.hibernate.core)

    // JPA / Hibernate
    implementation(libs.bluetape4k.hibernate)

    // QueryDsl
    implementation(libs.querydsl.jpa.get().toString() + ":jakarta")
    kapt(libs.querydsl.apt.get().toString() + ":jakarta")
    kaptTest(libs.querydsl.apt.get().toString() + ":jakarta")

    implementation(libs.hikaricp)
    implementation(libs.h2.v2)

    // Vaidators
    implementation(libs.hibernate.validator.lib)
    runtimeOnly(libs.jakarta.validation.api)


    // Spring Boot
    testImplementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.data.jpa.lib)
    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.springmockk)

    // Bluetape4k
    implementation(libs.bluetape4k.idgenerators)
    testImplementation(libs.bluetape4k.spring.boot4.core)

    // Mockk
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
}
