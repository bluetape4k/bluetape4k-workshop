package io.bluetape4k.workshop.exposed.domain.h2

import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.BEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YEntity
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import kotlin.properties.Delegates
import kotlin.test.assertFailsWith

class MultiDatabaseEntityTest {

    private val db1 by lazy {
        Database.connect(
            "jdbc:h2:mem:db1;DB_CLOSE_DELAY=-1;",
            "org.h2.Driver",
            "root",
            "",
            databaseConfig = DatabaseConfig {
                defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
            }
        )
    }
    private val db2 by lazy {
        Database.connect(
            "jdbc:h2:mem:db2;DB_CLOSE_DELAY=-1;",
            "org.h2.Driver",
            "root",
            "",
            databaseConfig = DatabaseConfig {
                defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
            }
        )
    }

    private var currentDB: Database? = null

    @BeforeEach
    fun beforeEach() {
        Assumptions.assumeTrue { TestDB.H2 in TestDB.enabledDialects() }

        if (TransactionManager.isInitialized()) {
            currentDB = TransactionManager.currentOrNull()?.db
        }
        transaction(db1) {
            SchemaUtils.create(EntityTestData.XTable, EntityTestData.YTable)
        }
        transaction(db2) {
            SchemaUtils.create(EntityTestData.XTable, EntityTestData.YTable)
        }
    }

    @AfterEach
    fun afterEach() {
        if (TestDB.H2 in TestDB.enabledDialects()) {
            TransactionManager.resetCurrent(currentDB?.transactionManager)
            transaction(db1) {
                SchemaUtils.drop(EntityTestData.XTable, EntityTestData.YTable)
            }
            transaction(db2) {
                SchemaUtils.drop(EntityTestData.XTable, EntityTestData.YTable)
            }
        }
    }

    @Test
    fun `simple create entities in different database`() {
        transaction(db1) {
            XEntity.new {
                this.b1 = true
            }
        }
        transaction(db2) {
            XEntity.new {
                this.b1 = false
            }
            XEntity.new {
                this.b1 = false
            }
        }

        transaction(db1) {
            XEntity.all().count() shouldBeEqualTo 1L
            XEntity.all().single().b1.shouldBeTrue()
        }
        transaction(db2) {
            XEntity.all().count() shouldBeEqualTo 2L
            XEntity.all().all { !it.b1 }.shouldBeTrue()
        }
    }

    @Test
    fun `embedded inserts in different database`() {
        transaction(db1) {
            XEntity.new {
                this.b1 = true
            }
            XEntity.all().count() shouldBeEqualTo 1L
            XEntity.all().single().b1.shouldBeTrue()

            transaction(db2) {
                XEntity.all().count() shouldBeEqualTo 0L
                XEntity.new {
                    this.b1 = false
                }
                XEntity.new {
                    this.b1 = false
                }
                XEntity.all().count() shouldBeEqualTo 2L
                XEntity.all().all { !it.b1 }.shouldBeTrue()
            }

            XEntity.all().count() shouldBeEqualTo 1L
            XEntity.all().single().b1.shouldBeTrue()
        }
    }

    @Test
    fun `embedded inserts in different database depth 2`() {
        transaction(db1) {
            XEntity.new {
                this.b1 = true
            }
            XEntity.all().count() shouldBeEqualTo 1L
            XEntity.all().single().b1.shouldBeTrue()

            transaction(db2) {
                XEntity.all().count() shouldBeEqualTo 0L
                XEntity.new {
                    this.b1 = false
                }
                XEntity.new {
                    this.b1 = false
                }
                XEntity.all().count() shouldBeEqualTo 2L
                XEntity.all().all { !it.b1 }.shouldBeTrue()

                transaction(db1) {
                    XEntity.new {
                        this.b1 = true
                    }
                    XEntity.new {
                        this.b1 = false
                    }
                    XEntity.all().count() shouldBeEqualTo 3L
                }
                XEntity.all().count() shouldBeEqualTo 2L
            }

            XEntity.all().count() shouldBeEqualTo 3L
            XEntity.all().map { it.b1 } shouldBeEqualTo listOf(true, true, false)
        }
    }

    @Test
    fun `cross references allowed for entities from same database`() {
        var db1b1 by Delegates.notNull<BEntity>()
        var db2b1 by Delegates.notNull<BEntity>()
        var db1y1 by Delegates.notNull<YEntity>()
        var db2y1 by Delegates.notNull<YEntity>()

        transaction(db1) {
            db1b1 = BEntity.new {}

            transaction(db2) {
                BEntity.count() shouldBeEqualTo 0L
                db2b1 = BEntity.new(2) {}
                db2y1 = YEntity.new("2") {}
                db2b1.y = db2y1
            }
            BEntity.count() shouldBeEqualTo 1L
            BEntity[1].shouldNotBeNull()

            db1y1 = YEntity.new("1") {}
            db1b1.y = db1y1

            commit()

            transaction(db2) {
                BEntity.testCache(EntityID(2, BEntity.table)).shouldBeNull()
                val b2Reread = BEntity.all().single()
                b2Reread.id shouldBeEqualTo db2b1.id
                b2Reread.y?.id shouldBeEqualTo db2y1.id
                b2Reread.y = null
            }
        }

        inTopLevelTransaction(Connection.TRANSACTION_READ_COMMITTED, db = db1) {
            maxAttempts = 1
            BEntity.testCache(db1b1.id).shouldBeNull()

            val b1Reread = BEntity[db1b1.id]
            b1Reread.id shouldBeEqualTo db1b1.id
            YEntity[db1y1.id].id shouldBeEqualTo db1y1.id
            b1Reread.y?.id shouldBeEqualTo db1y1.id
        }
    }

    @Test
    fun `cross referernces prohibited entities from different db`() {
        assertFailsWith<IllegalStateException> {
            transaction(db1) {
                val db1b1 = BEntity.new(1) {}

                transaction(db2) {
                    BEntity.count() shouldBeEqualTo 0L
                    db1b1.y = YEntity.new("2") {}
                }
            }
        }
    }
}
