plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.exposed)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.exposed.javers"
        databaseUrl = "jdbc:h2:mem:workshop-exposed-javers-audit-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

dependencies {
    // bluetape4k JaVers integration
    implementation(libs.bluetape4k.javers.core)

    // JetBrains Exposed
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    // H2 in-memory database
    runtimeOnly(libs.h2.v2)

    // Test
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.exposed.jdbc.tests)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.postgresql.driver)
}
