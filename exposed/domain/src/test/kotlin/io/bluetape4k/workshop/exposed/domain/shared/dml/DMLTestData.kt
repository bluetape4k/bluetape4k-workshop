package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import java.math.BigDecimal

object DMLTestData {

    object Cities: Table() {
        val id = integer("city_id").autoIncrement()
        val name = varchar("name", 50)

        override val primaryKey = PrimaryKey(id)
    }

    object Users: Table() {
        val id = varchar("id", 10)
        val name = varchar("name", 50)
        val cityId = reference("city_id", Cities.id).nullable()
        val flags = integer("flags").default(0)

        override val primaryKey = PrimaryKey(id)

        object Flags {
            const val IS_ADMIN = 0b1
            const val HAS_DATA = 0b1000
        }
    }

    object UserData: Table() {
        val userId = reference("user_id", Users.id)
        val comment = varchar("comment", 30)
        val value = integer("value")
    }

    object Sales: Table() {
        val year = integer("year")
        val month = integer("month")
        val product = varchar("product", 30).nullable()
        val amount = decimal("amount", 8, 2)
    }

    object SomeAmounts: Table() {
        val amount = decimal("amount", 8, 2)
    }
}

fun AbstractExposedTest.withCitiesAndUsers(
    testDB: TestDB,
    statement: Transaction.(
        cities: DMLTestData.Cities,
        users: DMLTestData.Users,
        userData: DMLTestData.UserData,
    ) -> Unit,
) {
    val users = DMLTestData.Users
    val userFlags = DMLTestData.Users.Flags
    val cities = DMLTestData.Cities
    val userData = DMLTestData.UserData

    withTables(testDB, cities, users, userData) {
        val saintPetersburgId = cities.insert {
            it[name] = "St. Petersburg"
        } get cities.id

        val munichId = cities.insert {
            it[name] = "Munich"
        } get cities.id

        cities.insert {
            it[name] = "Prague"
        }

        users.insert {
            it[id] = "andrey"
            it[name] = "Andrey"
            it[cityId] = saintPetersburgId
            it[flags] = userFlags.IS_ADMIN
        }

        users.insert {
            it[id] = "sergey"
            it[name] = "Sergey"
            it[cityId] = munichId
            it[flags] = userFlags.IS_ADMIN or userFlags.HAS_DATA
        }

        users.insert {
            it[id] = "eugene"
            it[name] = "Eugene"
            it[cityId] = munichId
            it[flags] = userFlags.HAS_DATA
        }

        users.insert {
            it[id] = "alex"
            it[name] = "Alex"
            it[cityId] = null
        }

        users.insert {
            it[id] = "smth"
            it[name] = "Something"
            it[cityId] = null
            it[flags] = userFlags.HAS_DATA
        }

        userData.insert {
            it[userId] = "smth"
            it[comment] = "Something is here"
            it[value] = 10
        }

        userData.insert {
            it[userId] = "smth"
            it[comment] = "Comment #2"
            it[value] = 20
        }

        userData.insert {
            it[userId] = "eugene"
            it[comment] = "Comment for Eugene"
            it[value] = 20
        }

        userData.insert {
            it[userId] = "sergey"
            it[comment] = "Comment for Sergey"
            it[value] = 30
        }

        statement(cities, users, userData)
    }
}

fun AbstractExposedTest.withSales(
    dialect: TestDB,
    statement: Transaction.(testDb: TestDB, sales: DMLTestData.Sales) -> Unit,
) {
    val sales = DMLTestData.Sales

    withTables(dialect, sales) { testDb ->
        insertSale(2018, 11, "tea", "550.10")
        insertSale(2018, 12, "coffee", "1500.25")
        insertSale(2018, 12, "tea", "900.30")
        insertSale(2019, 1, "coffee", "1620.10")
        insertSale(2019, 1, "tea", "650.70")
        insertSale(2019, 2, "coffee", "1870.90")
        insertSale(2019, 2, null, "10.20")

        statement(testDb, sales)
    }
}

private fun insertSale(year: Int, month: Int, product: String?, amount: String) {
    val sales = DMLTestData.Sales
    sales.insert {
        it[sales.year] = year
        it[sales.month] = month
        it[sales.product] = product
        it[sales.amount] = amount.toBigDecimal()
    }
}

fun AbstractExposedTest.withSomeAmounts(
    dialect: TestDB,
    statement: Transaction.(testDb: TestDB, someAmounts: DMLTestData.SomeAmounts) -> Unit,
) {
    val someAmounts = DMLTestData.SomeAmounts

    withTables(dialect, someAmounts) {
        fun insertAmount(amount: BigDecimal) {
            someAmounts.insert {
                it[someAmounts.amount] = amount
            }
        }
        insertAmount("650.70".toBigDecimal())
        insertAmount("1500.25".toBigDecimal())
        insertAmount("1000.00".toBigDecimal())

        statement(it, someAmounts)
    }
}

fun AbstractExposedTest.withSalesAndSomeAmounts(
    dialect: TestDB,
    statement: Transaction.(
        testDb: TestDB,
        sales: DMLTestData.Sales,
        someAmounts: DMLTestData.SomeAmounts,
    ) -> Unit,
) {
    val sales = DMLTestData.Sales
    val someAmounts = DMLTestData.SomeAmounts

    withTables(dialect, sales, someAmounts) { testDb ->
        insertSale(2018, 11, "tea", "550.10")
        insertSale(2018, 12, "coffee", "1500.25")
        insertSale(2018, 12, "tea", "900.30")
        insertSale(2019, 1, "coffee", "1620.10")
        insertSale(2019, 1, "tea", "650.70")
        insertSale(2019, 2, "coffee", "1870.90")
        insertSale(2019, 2, null, "10.20")

        fun insertAmount(amount: BigDecimal) {
            someAmounts.insert {
                it[someAmounts.amount] = amount
            }
        }
        insertAmount("650.70".toBigDecimal())
        insertAmount("1500.25".toBigDecimal())
        insertAmount("1000.00".toBigDecimal())


        statement(testDb, sales, someAmounts)
    }
}


object Orgs: IntIdTable() {
    val uid = varchar("uid", 36).uniqueIndex().clientDefault { TimebasedUuid.nextBase62String() }
    val name = varchar("name", 255)
}

class Org(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<Org>(Orgs)

    var uid by Orgs.uid
    var name by Orgs.name
}

object OrgMemberships: IntIdTable() {
    val orgId = reference("org", Orgs.uid)
}

class OrgMembership(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<OrgMembership>(OrgMemberships)

    var orgId by OrgMemberships.orgId
    var org by Org referencedOn OrgMemberships.orgId
}

internal fun Iterable<ResultRow>.toCityNameList(): List<String> =
    map { it[DMLTestData.Cities.name] }
