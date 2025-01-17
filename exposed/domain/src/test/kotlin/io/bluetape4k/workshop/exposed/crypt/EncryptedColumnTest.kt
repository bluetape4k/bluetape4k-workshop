package io.bluetape4k.workshop.exposed.crypt

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import nl.altindag.log.LogCaptor
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainNone
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldStartWith
import org.jetbrains.exposed.crypt.Algorithms
import org.jetbrains.exposed.crypt.Encryptor
import org.jetbrains.exposed.crypt.encryptedBinary
import org.jetbrains.exposed.crypt.encryptedVarchar
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.exposedLogger
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EncryptedColumnTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `output length of encryption`(testDB: TestDB) {
        fun assertSize(encryptor: Encryptor, str: String) {
            encryptor.encrypt(str).toUtf8Bytes().size shouldBeEqualTo encryptor.maxColLength(str.toUtf8Bytes().size)
        }

        val encryptors = arrayOf(
            "AES_256_PBE_GCM" to Algorithms.AES_256_PBE_GCM("passwd", "12345678"),
            "AES_256_PBE_CBC" to Algorithms.AES_256_PBE_CBC("passwd", "12345678"),
            "BLOW_FISH" to Algorithms.BLOW_FISH("sadsad"),
            "TRIPLE_DES" to Algorithms.TRIPLE_DES("1".repeat(24))
        )
        val testString = arrayOf(
            "1",
            "2".repeat(10),
            "3".repeat(31),
            "4".repeat(1001),
            "5".repeat(5391)
        )

        encryptors.forEach { (algorithm, encryptor) ->
            testString.forEach { testStr ->
                log.debug { "Testing $algorithm, str length=${testStr.length}" }
                assertSize(encryptor, testStr)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `encrypted column type with a string`(testDB: TestDB) {
        val nameEncryptor = Algorithms.AES_256_PBE_CBC("passwd", "5c0744940b5c369b")
        val stringTable = object: IntIdTable("StringTable") {
            val name = encryptedVarchar("name", 80, nameEncryptor)
            val city = encryptedVarchar("city", 80, Algorithms.AES_256_PBE_GCM("passwd", "5c0744940b5c369b"))
            val address = encryptedVarchar("address", 100, Algorithms.BLOW_FISH("key"))
            val age = encryptedVarchar("age", 100, Algorithms.TRIPLE_DES("1".repeat(24)))
        }

        withTables(testDB, stringTable) {
            val logCaptor = LogCaptor.forName(exposedLogger.name)
            logCaptor.setLogLevelToDebug()

            val insertedStrings = listOf("testName", "testCity", "testAddress", "testAge")
            val (insertedName, insertedCity, insertedAddress, insertedAge) = insertedStrings
            val id1 = stringTable.insertAndGetId {
                it[name] = insertedName
                it[city] = insertedCity
                it[address] = insertedAddress
                it[age] = insertedAge
            }

            val insertLog = logCaptor.debugLogs.single()
            insertLog.shouldStartWith("INSERT ")
            insertLog.shouldContainNone(insertedStrings)

            logCaptor.clearLogs()
            logCaptor.resetLogLevel()
            logCaptor.close()

            stringTable.selectAll().count().toInt() shouldBeEqualTo 1

            stringTable.selectAll()
                .where { stringTable.id eq id1 }
                .first()[stringTable.name] shouldBeEqualTo insertedName

            stringTable.selectAll()
                .where { stringTable.id eq id1 }
                .first()[stringTable.city] shouldBeEqualTo insertedCity


            stringTable.selectAll()
                .where { stringTable.id eq id1 }
                .first()[stringTable.address] shouldBeEqualTo insertedAddress


            stringTable.selectAll()
                .where { stringTable.id eq id1 }
                .first()[stringTable.age] shouldBeEqualTo insertedAge

            /**
             * TODO: 암호화된 컬럼으로 검색은 불가능하다. --> 이거 개선해야 한다.
             */
            assertFailsWith<AssertionError> {
                stringTable.selectAll()
                    .where { stringTable.name eq nameEncryptor.encrypt(insertedName) }
                    .shouldNotBeEmpty()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update encrypted column type`(testDB: TestDB) {
        val stringTable = object: IntIdTable("StringTable") {
            val name = encryptedVarchar("name", 100, Algorithms.AES_256_PBE_GCM("passwd", "12345678"))
            val city = encryptedBinary("city", 100, Algorithms.AES_256_PBE_CBC("passwd", "12345678"))
            val address = encryptedVarchar("address", 100, Algorithms.BLOW_FISH("key"))
        }

        withTables(testDB, stringTable) {
            val logCaptor = LogCaptor.forName(exposedLogger.name)
            logCaptor.setLogLevelToDebug()

            val insertedStrings = listOf("TestName", "TestCity", "TestAddress")
            val (insertedName, insertedCity, insertedAddress) = insertedStrings
            val id = stringTable.insertAndGetId {
                it[name] = insertedName
                it[city] = insertedCity.toUtf8Bytes()
                it[address] = insertedAddress
            }

            val insertLog = logCaptor.debugLogs.single()
            insertLog.shouldStartWith("INSERT ")
            insertLog.shouldContainNone(insertedStrings)

            logCaptor.clearLogs()

            val updatedStrings = listOf("TestName2", "TestCity2", "TestAddress2")
            val (updatedName, updatedCity, updatedAddress) = updatedStrings
            stringTable.update({ stringTable.id eq id }) {
                it[name] = updatedName
                it[city] = updatedCity.toUtf8Bytes()
                it[address] = updatedAddress
            }

            val updateLog = logCaptor.debugLogs.single()
            updateLog.shouldStartWith("UPDATE ")
            updateLog.shouldContainNone(updatedStrings)

            logCaptor.clearLogs()
            logCaptor.resetLogLevel()
            logCaptor.close()

            stringTable.selectAll().count().toInt() shouldBeEqualTo 1

            stringTable.selectAll()
                .where { stringTable.id eq id }
                .first()[stringTable.name] shouldBeEqualTo updatedName

            stringTable.selectAll()
                .where { stringTable.id eq id }
                .first()[stringTable.city]
                .toUtf8String() shouldBeEqualTo updatedCity

            stringTable.selectAll()
                .where { stringTable.id eq id }
                .first()[stringTable.address] shouldBeEqualTo updatedAddress
        }
    }
}
