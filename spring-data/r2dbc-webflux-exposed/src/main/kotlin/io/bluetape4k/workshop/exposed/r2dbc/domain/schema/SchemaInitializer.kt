package io.bluetape4k.workshop.exposed.r2dbc.domain.schema

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.schema.UserSchema.UserTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component

@Component
class SchemaInitializer(private val database: R2dbcDatabase): ApplicationListener<ApplicationReadyEvent> {

    companion object: KLoggingChannel()

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        log.info { "샘플 데이터 추가" }

        runBlocking(Dispatchers.IO) {
            suspendTransaction(db = database) {
                createSchema()
                populateData()
            }
        }
    }

    private suspend fun createSchema() {
        log.info { "Create schema ..." }
        SchemaUtils.create(UserTable)
        log.info { "Schema created!" }
    }

    private suspend fun populateData() {
        val totalUsers = UserTable.selectAll().count()

        if (totalUsers > 0) {
            log.info { "There are already $totalUsers users in the database. Skip populating data." }
            return
        }

        log.info { "Insert sample users ..." }

        val users = List(4) {
            val i = it + 1
            UserRecord("User no $i", "user$i", "user$i@users.com", "user$i.png")
        }

        UserTable.batchInsert(users) {
            this[UserTable.name] = it.name
            this[UserTable.login] = it.login
            this[UserTable.email] = it.email
            this[UserTable.avatar] = it.avatar
        }
    }
}
