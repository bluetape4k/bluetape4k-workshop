package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchReplace
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.longLiteral
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.trim
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ReplaceTest: AbstractExposedTest() {

    private val replaceSupported = TestDB.ALL_MYSQL_LIKE

    private object NewAuth: Table("new_auth") {
        val username = varchar("username", 16)
        val session = binary("session", 64)
        val timestamp = long("timestamp").default(0)
        val serverID = varchar("serverID", 64).default("")

        override val primaryKey = PrimaryKey(username)
    }

    /**
     * Test for [Table.replace] function.
     *
     * ```sql
     * REPLACE INTO new_auth (username, `session`) VALUES ('username1', session)
     * REPLACE INTO new_auth (username, `session`) VALUES ('username2', session)
     * ```
     *
     * ```sql
     * REPLACE INTO new_auth (username, `session`, `timestamp`, serverID)
     *  SELECT new_auth.username, new_auth.`session`, 1736313121797, 'special server id'
     *    FROM new_auth
     * ```
     *
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `replaice select`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        withTables(testDb, NewAuth) {

            // inserts 2 new non-conflict rows with defaults
            NewAuth.batchReplace(listOf("username1", "username2")) {
                this[NewAuth.username] = it
                this[NewAuth.session] = "session".toByteArray()
            }

            val result1 = NewAuth.selectAll().toList()
            result1.all { it[NewAuth.timestamp] == 0L }.shouldBeTrue()
            result1.all { it[NewAuth.serverID].isEmpty() }.shouldBeTrue()

            val timeNow = System.currentTimeMillis()
            val specialId = "special server id"
            val allRowsWithNewDefaults: Query = NewAuth.select(
                NewAuth.username,
                NewAuth.session,
                longLiteral(timeNow),
                stringLiteral(specialId)
            )

            val affectedRowCount = NewAuth.replace(allRowsWithNewDefaults)

            // MySQL returns 1 for every insert + 1 for every delete on conflict, while others only count inserts
            val expectedRowCount = if (testDb in TestDB.ALL_MYSQL_LIKE) 4 else 2
            affectedRowCount shouldBeEqualTo expectedRowCount

            val result2 = NewAuth.selectAll().toList()
            result2.all { it[NewAuth.timestamp] == timeNow }.shouldBeTrue()
            result2.all { it[NewAuth.serverID] == specialId }.shouldBeTrue()
        }
    }

    /**
     * Test for [Table.replace] function with specific columns.
     *
     * ```sql
     * REPLACE INTO new_auth (username, `session`) VALUES ('username1', session1)
     * REPLACE INTO new_auth (username, `session`) VALUES ('username2', session1)
     * ```
     * ```sql
     * REPLACE INTO new_auth (username, `session`)
     *  SELECT new_auth.username, 'session2'
     *    FROM new_auth
     *   WHERE new_auth.username = 'username1'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `replace select with specific columns`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        withTables(testDb, NewAuth) {
            val (name1, name2, oldSession) = Triple("username1", "username2", "session1".toByteArray())

            NewAuth.batchReplace(listOf(name1, name2)) {
                this[NewAuth.username] = it
                this[NewAuth.session] = oldSession
            }

            val newSession = "session2"
            val name1Row = NewAuth
                .select(NewAuth.username, stringLiteral(newSession))
                .where { NewAuth.username eq name1 }

            val affectedRowCount = NewAuth.replace(name1Row, columns = listOf(NewAuth.username, NewAuth.session))

            // MySQL returns 1 for every insert + 1 for every delete on conflict, while others only count inserts
            val expectedRowCount = if (testDb in TestDB.ALL_MYSQL_LIKE) 2 else 1
            affectedRowCount shouldBeEqualTo expectedRowCount

            NewAuth.selectAll()
                .where { NewAuth.username eq name1 }
                .single()[NewAuth.session] shouldBeEqualTo newSession.toByteArray()

            NewAuth.selectAll()
                .where { NewAuth.username eq name2 }
                .single()[NewAuth.session] shouldBeEqualTo oldSession
        }
    }

    /**
     * ```sql
     * REPLACE INTO new_auth (username, `session`) VALUES ('username', session)
     * ```
     *
     * ```sql
     * REPLACE INTO new_auth (username, `session`, `timestamp`, serverID)
     *  VALUES ('username', session, 1736314898253, 'username-session')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `replace with PK conflict`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        withTables(testDb, NewAuth) {
            val (name1, session1) = "username" to "session"

            NewAuth.replace {
                it[username] = name1
                it[session] = session1.toByteArray()
            }

            val result1 = NewAuth.selectAll().single()
            result1[NewAuth.timestamp] shouldBeEqualTo 0L
            result1[NewAuth.serverID].shouldBeEmpty()

            val timeNow = System.currentTimeMillis()
            val concatId = "$name1-$session1"
            NewAuth.replace {
                it[username] = name1
                it[session] = session1.toByteArray()
                it[timestamp] = timeNow
                it[serverID] = concatId
            }

            val result2 = NewAuth.selectAll().single()
            result2[NewAuth.timestamp] shouldBeEqualTo timeNow
            result2[NewAuth.serverID] shouldBeEqualTo concatId
        }
    }

    /**
     * ```sql
     * REPLACE INTO test_table (key_1, key_2) VALUES ('A', 'B')
     * ```
     *
     * ```sql
     * REPLACE INTO test_table (key_1, key_2, replaced) VALUES ('A', 'B 2', 1736315489343)
     * ```
     *
     * ```sql
     * REPLACE INTO test_table (key_1, key_2, replaced) VALUES ('A', 'B', 1736315489343)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `replace With Composite PK Conflict`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        val tester = object: Table("test_table") {
            val key1 = varchar("key_1", 16)
            val key2 = varchar("key_2", 16)
            val replaced = long("replaced").default(0)

            override val primaryKey = PrimaryKey(key1, key2)
        }

        withTables(testDb, tester) {
            val (id1, id2) = "A" to "B"
            tester.replace {
                it[key1] = id1
                it[key2] = id2
            }

            tester.selectAll().single()[tester.replaced] shouldBeEqualTo 0L

            val timeNow = System.currentTimeMillis()
            tester.replace { // insert because only 1 constraint is equal
                it[key1] = id1
                it[key2] = "$id2 2"
                it[replaced] = timeNow
            }

            tester.selectAll().count() shouldBeEqualTo 2L
            tester.selectAll()
                .where { tester.key2 eq id2 }
                .single()[tester.replaced] shouldBeEqualTo 0L

            tester.replace { // delete & insert because both constraints match
                it[key1] = id1
                it[key2] = id2
                it[replaced] = timeNow
            }

            tester.selectAll().count() shouldBeEqualTo 2L
            tester.selectAll()
                .where { tester.key2 eq id2 }
                .single()[tester.replaced] shouldBeEqualTo timeNow
        }
    }

    /**
     * ```sql
     * REPLACE INTO new_auth (username, `session`, serverID)
     *  VALUES ('username', session, TRIM('  serverID1 '))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `replace With Expression`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        withTables(testDb, NewAuth) {
            NewAuth.replace {
                it[username] = "username"
                it[session] = "session".toByteArray()
                it[serverID] = stringLiteral("  serverID1 ").trim()
            }

            NewAuth.selectAll().single()[NewAuth.serverID] shouldBeEqualTo "serverID1"
        }
    }

    /**
     * ```sql
     * REPLACE INTO tester  () VALUES ()
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `empty replace`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        val tester = object: Table("tester") {
            val id = integer("id").autoIncrement()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDb, tester) {
            tester.replace { }
            tester.selectAll().count() shouldBeEqualTo 1L
        }
    }

    /**
     * ```sql
     * REPLACE INTO Cities (city_id, `name`) VALUES (2, 'München')
     * REPLACE INTO Cities (city_id, `name`) VALUES (3, 'Prague')
     * REPLACE INTO Cities (city_id, `name`) VALUES (1, 'Saint Petersburg')
     *
     * SELECT Cities.`name`
     *   FROM Cities
     *  WHERE Cities.city_id IN (2, 3, 1)
     *  ORDER BY Cities.`name` ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Batch Replace 01`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            val (munichId, pragueId, saintPetersburgId) = cities
                .select(cities.id)
                .where { cities.name inList listOf("Munich", "Prague", "St. Petersburg") }
                .orderBy(cities.name)
                .map { it[cities.id] }

            // replace is implemented as delete-then-insert on conflict, which breaks foreign key constraints,
            // so this test will only work if those related rows are deleted.
            userData.deleteAll()
            users.deleteAll()

            val cityUpdates = listOf(
                munichId to "München",
                pragueId to "Prague",
                saintPetersburgId to "Saint Petersburg"
            )

            cities.batchReplace(cityUpdates) {
                this[cities.id] = it.first
                this[cities.name] = it.second
            }

            val cityNames = cities.select(cities.name)
                .where { cities.id inList cityUpdates.unzip().first }
                .orderBy(cities.name)
                .toCityNameList()

            cityNames shouldBeEqualTo cityUpdates.unzip().second
        }
    }

    /**
     * ```sql
     * REPLACE INTO Cities (city_id, `name`) VALUES (1, '2ykXqvsnet6hF1DQ3MZES')
     * REPLACE INTO Cities (city_id, `name`) VALUES (2, '2ykXqvsnet6hF1DQ3MZET')
     * ...
     *
     * REPLACE INTO Cities (city_id, `name`) VALUES (25, '2ykXqvstMbau7kfk3waN1')
     * ```
     *
     * ```sql
     * REPLACE INTO Cities (city_id, `name`) VALUES (1, '2ykXqvuSHinAALEdvudUU')
     * REPLACE INTO Cities (city_id, `name`) VALUES (2, '2ykXqvuSHinAALEdvudUV')
     * ...
     * REPLACE INTO Cities (city_id, `name`) VALUES (25, '2ykXqvuYMuZ4nJhD0Fbob')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Batch Replace With Sequence`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        val cities = DMLTestData.Cities
        withTables(testDb, cities) {
            val amountOfNames = 25
            val names = List(amountOfNames) { index ->
                index + 1 to TimebasedUuid.Epoch.nextIdAsString()
            }.asSequence()

            cities.batchReplace(names) { (index, name) ->
                this[cities.id] = index
                this[cities.name] = name
            }

            val namesFromDB1 = cities.selectAll().toCityNameList()
            namesFromDB1.size shouldBeEqualTo amountOfNames
            namesFromDB1 shouldBeEqualTo names.unzip().second

            val namesToReplace = List(amountOfNames) { index ->
                index + 1 to TimebasedUuid.Epoch.nextIdAsString()
            }.asSequence()

            cities.batchReplace(namesToReplace) { (index, name) ->
                this[cities.id] = index
                this[cities.name] = name
            }

            val namesFromDB2 = cities.selectAll().toCityNameList()
            namesFromDB2.size shouldBeEqualTo amountOfNames
            namesFromDB2 shouldBeEqualTo namesToReplace.unzip().second
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch Replace With Empty Sequence`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in replaceSupported }

        val cities = DMLTestData.Cities
        withTables(testDb, cities) {
            val names = emptySequence<String>()
            cities.batchReplace(names) { name -> this[cities.name] = name }

            cities.selectAll().count().toInt() shouldBeEqualTo 0
        }
    }
}
