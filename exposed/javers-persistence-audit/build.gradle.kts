plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.exposed)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.exposed.javers.persistence"
        databaseUrl = "jdbc:h2:mem:workshop-exposed-javers-persistence-audit-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.javers.core)
    implementation(libs.bluetape4k.javers.persistence.kafka)
    implementation(libs.bluetape4k.javers.persistence.redis)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.bluetape4k.redisson)
    implementation(libs.kafka.clients)
    implementation(libs.lettuce.core)
    implementation(libs.redisson.lib)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    runtimeOnly(libs.h2.v2)

    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.kafka)
}
