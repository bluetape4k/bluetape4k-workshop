package io.bluetape4k.workshop.mongodb.config

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import io.bluetape4k.testcontainers.storage.MongoDBServer
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories

@Configuration
@EnableMongoRepositories(basePackages = ["io.bluetape4k.workshop.mongodb.domain"])
class MongoClientConfig: AbstractMongoClientConfiguration() {

    override fun getDatabaseName(): String = "people"

    override fun configureClientSettings(builder: MongoClientSettings.Builder) {
        builder.applyConnectionString(ConnectionString(MongoDBServer.Launcher.mongoDB.connectionString))
    }
}
