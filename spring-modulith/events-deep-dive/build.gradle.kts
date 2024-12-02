plugins {
    kotlin("plugin.spring")
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
    kotlin("plugin.jpa")
    kotlin("kapt")
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
    }
}

@Suppress("UnstableApiUsage") configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Spring Modulith
    implementation(Libs.spring_modulith_starter_jpa)
    testImplementation(Libs.spring_modulith_starter_test)

    api(Libs.jakarta_annotation_api)
    api(Libs.jakarta_persistence_api)
    api(Libs.hibernate_core)

    // Spring Boot
    implementation(Libs.springBootStarter("data-jpa"))
    implementation(Libs.springBootStarter("validation"))
    testImplementation(Libs.springBoot("autoconfigure"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // JPA / Hibernate
    implementation(Libs.bluetape4k_hibernate)

    // QueryDsl
    implementation(Libs.querydsl_jpa + ":jakarta")
    kapt(Libs.querydsl_apt + ":jakarta")
    kaptTest(Libs.querydsl_apt + ":jakarta")

    implementation(Libs.hikaricp)
    implementation(Libs.h2_v2)

    // Vaidators
    implementation(Libs.hibernate_validator)
    runtimeOnly(Libs.jakarta_validation_api)

    implementation(Libs.bluetape4k_idgenerators)
    testImplementation(Libs.bluetape4k_spring_tests)

    testImplementation(Libs.springmockk)
}
