/**
 * jmolecules-bom 을 사용하기 위해서는 아래와 같이 buildscript 에서 platform 을 추가해야 합니다.
 */
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(platform("org.jmolecules:jmolecules-bom:2023.2.1"))
        classpath("org.jmolecules.integrations:jmolecules-bytebuddy")
    }
}

plugins {
    kotlin("plugin.spring")
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
    kotlin("plugin.jpa")
    kotlin("kapt")
    id(Plugins.spring_boot)
    id("net.bytebuddy.byte-buddy-gradle-plugin") version "1.15.10"
}

// JPA Entities 들을 Java와 같이 모두 override 가능하게 합니다 (Kotlin 은 기본이 final 입니다)
// 이렇게 해야 association의 proxy 가 만들어집니다.
// https://kotlinlang.org/docs/reference/compiler-plugins.html
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.jmolecules.example.KotlinExampleKt")
}

byteBuddy {
    transformation {
        plugin = org.jmolecules.bytebuddy.JMoleculesPlugin::class.java
    }
}

//// 태스크 의존성 설정
//tasks.named("jar") { dependsOn(tasks.withType<ByteBuddyTask>()) }
//tasks.named("bootJar") { dependsOn(tasks.withType<ByteBuddyTask>()) }
//tasks.named("test") { dependsOn(tasks.withType<ByteBuddyTask>()) }

dependencies {
    implementation(platform("org.jmolecules:jmolecules-bom:2023.2.1"))  // https://mvnrepository.com/artifact/org.jmolecules/jmolecules-bom
    implementation("org.jmolecules:jmolecules-ddd")
    implementation("org.jmolecules:kmolecules-ddd")
    implementation("org.jmolecules:jmolecules-events")

    implementation("org.jmolecules.integrations:jmolecules-apt")
    annotationProcessor("org.jmolecules.integrations:jmolecules-apt")

    implementation("org.jmolecules.integrations:jmolecules-ddd-integration")
    implementation("org.jmolecules.integrations:jmolecules-jackson")
    implementation("org.jmolecules.integrations:jmolecules-jpa")
    implementation("org.jmolecules.integrations:jmolecules-spring")
    implementation("org.jmolecules.integrations:jmolecules-bytebuddy")
    // implementation("org.jmolecules.integrations:jmolecules-bytebuddy-nodep")

    implementation("org.jmolecules.integrations:jmolecules-starter-ddd")
    testImplementation("org.jmolecules.integrations:jmolecules-starter-test")

    // Bluetape4k
    implementation(Libs.bluetape4k_io)
    implementation(Libs.bluetape4k_jackson)
    implementation(Libs.bluetape4k_idgenerators)
    implementation(Libs.bluetape4k_spring_core)
    testImplementation(Libs.bluetape4k_spring_tests)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("data-jpa"))
    implementation(Libs.hikaricp)
    implementation(Libs.h2_v2)

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(Libs.assertj_core)
}
