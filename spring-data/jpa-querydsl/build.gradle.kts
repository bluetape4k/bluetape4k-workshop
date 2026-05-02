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
    implementation(libs.bluetape4k.hibernate)

    // NOTE: Java 9+ 환경에서 kapt가 제대로 동작하려면 javax.annotation-api 를 참조해야 합니다.
    // api(libs.javax.annotation.api)
    api(libs.jakarta.annotation.api)

    api(libs.jakarta.persistence.api)
    api(libs.hibernate.core)

    // QueryDsl
    implementation(libs.querydsl.jpa.get().toString() + ":jakarta")
    kapt(libs.querydsl.apt.get().toString() + ":jakarta")
    kaptTest(libs.querydsl.apt.get().toString() + ":jakarta")

    // Vaidators
    implementation(libs.hibernate.validator.lib)
    runtimeOnly(libs.jakarta.validation.api)


    implementation(libs.spring.boot.starter.data.jpa.lib)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    implementation(libs.spring.boot.starter.validation)
    testImplementation(libs.spring.boot.autoconfigure.lib)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.bluetape4k.spring.boot4.core)

    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.mysql)

    testImplementation(libs.hikaricp)
    testImplementation(libs.h2.v2)
    testImplementation(libs.mysql.connector.j)

    // Caching 테스트
    // testImplementation(libs.bluetape4k.cache.lib)
    testImplementation(libs.hibernate.jcache)
    testImplementation(libs.caffeine.jcache)
}
