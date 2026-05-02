plugins {
    id("io.quarkus")
    alias(libs.plugins.kotlin.allopen)

    // NOTE: Quarkus 에서는 JPA 용 Entity를 open 으로 변경하는 것이 작동하지 않는다.
    // NOTE: 아럐 annotation("javax.persistence.Entity") 를 추가해주어야 한다
    alias(libs.plugins.kotlin.jpa)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")

    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // NOTE: Quarkus 는 꼭 gradle platform 으로 참조해야 제대로 빌드가 된다.
    implementation(platform(libs.quarkus.bom))
    implementation(platform(libs.quarkus.universe.bom))
    implementation(platform(libs.resteasy.bom))

    // Quarkus 라이브러리 (https://quarkus.io/extensions/)
    implementation("io.quarkus.quarkus-hibernate-reactive-panache")
    implementation("io.quarkus.quarkus-hibernate-validator")
    implementation("io.quarkus.quarkus-kotlin")
    implementation("io.quarkus.quarkus-smallrye-openapi")

    // 참고: https://quarkus.io/guides/hibernate-reactive-panache#testing
    implementation("io.quarkus.quarkus-vertx")
    testImplementation("io.quarkus.quarkus-test-vertx")
    testImplementation("io.quarkus.quarkus-test-hibernate-reactive-panache")

    // rest
    implementation("io.quarkus.quarkus-rest")
    implementation("io.quarkus.quarkus-rest-kotlin")
    implementation("io.quarkus.quarkus-rest-jackson")

    // see: https://quarkus.io/guides/datasource
    // rective datasource 는 mysql, postres 밖에 없다
    // implementation("io.quarkus.quarkus-reactive-mysql-client")
    implementation("io.quarkus.quarkus-reactive-pg-client")

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured.kotlin)

    // Bluetape4k
    implementation(libs.bluetape4k.jackson2)
    implementation(libs.bluetape4k.quarkus.core)
    testImplementation(libs.bluetape4k.quarkus.tests)
    testImplementation(libs.bluetape4k.idgenerators)

    // coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactive)
    testImplementation(libs.kotlinx.coroutines.test.lib)
}
