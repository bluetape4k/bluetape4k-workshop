package io.bluetape4k.workshop.mongodb.config

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import io.bluetape4k.testcontainers.storage.MongoDBServer
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.config.AbstractReactiveMongoConfiguration
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories

@Configuration
@EnableReactiveMongoRepositories(basePackages = ["io.bluetape4k.workshop.mongodb.domain"])
class ReactiveMongoConfig: AbstractReactiveMongoConfiguration() {

    override fun getDatabaseName(): String = "people"

    override fun configureClientSettings(builder: MongoClientSettings.Builder) {
        builder.applyConnectionString(ConnectionString(MongoDBServer.Launcher.mongoDB.connectionString))
    }
}
