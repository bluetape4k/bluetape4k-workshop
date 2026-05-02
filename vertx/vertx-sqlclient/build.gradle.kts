plugins {
    alias(libs.plugins.kotlin.kapt)
}

kapt {
    includeCompileClasspath = true
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.jdbc)
    testImplementation(libs.bluetape4k.junit5)

    // Vertx
    implementation(libs.bluetape4k.vertx)
    testImplementation(libs.vertx.junit5)

    // Vertx Kotlin
    implementation(libs.vertx.core)
    implementation(libs.vertx.lang.kotlin.lib)
    implementation(libs.vertx.lang.kotlin.coroutines)

    // Vertx SqlClient
    implementation(libs.vertx.sql.client.lib)
    implementation(libs.vertx.sql.client.templates)
    implementation(libs.vertx.mysql.client)
    implementation(libs.vertx.pg.client)

    // Vertx Jdbc (MySQL, Postgres 를 제외한 H2 같은 것은 기존 JDBC 를 Wrapping한 것을 사용합니다)
    implementation(libs.vertx.jdbc.client)
    implementation(libs.agroal.pool)

    // vertx-sql-cleint-templates 에서 @DataObject, @RowMapped 를 위해 사용
    compileOnly(libs.vertx.codegen)
    kapt(libs.vertx.codegen)
    kaptTest(libs.vertx.codegen)

    // MyBatis
    implementation(libs.mybatis.dynamic.sql)

    // Vetx SqlClient Templates 에서 Jackson Databind 를 이용한 매핑을 사용한다
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.datatype.jsr353)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    testImplementation(libs.h2.lib)
    testImplementation(libs.mysql.connector.j)

    // Testcontainers
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.lib)
    testImplementation(libs.testcontainers.mysql)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
}
