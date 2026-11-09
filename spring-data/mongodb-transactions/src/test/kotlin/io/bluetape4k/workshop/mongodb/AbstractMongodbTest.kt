package io.bluetape4k.workshop.mongodb

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.MongoDBServer
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class)
abstract class AbstractMongodbTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        val faker = Fakers.faker

        @JvmStatic
        val mongodb by lazy { MongoDBServer.Launcher.mongoDB }

        fun createMongoClient(): MongoClient = MongoClients.create(mongodb.url)

        fun createReactiveMongoClient(): com.mongodb.reactivestreams.client.MongoClient =
            com.mongodb.reactivestreams.client.MongoClients.create(mongodb.url)
    }

}
