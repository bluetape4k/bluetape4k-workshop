package io.bluetape4k.workshop.exposed.sql.crypt

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.crypt.Algorithms
import org.jetbrains.exposed.crypt.encryptedBinary
import org.jetbrains.exposed.crypt.encryptedVarchar
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EncryptedColumnDaoTest: AbstractExposedTest() {

    companion object: KLogging() {
        private val encryptor = Algorithms.AES_256_PBE_GCM("passwd", "12345678")
    }

    object TestTable: IntIdTable() {
        val varchar = encryptedVarchar("varchar", 100, encryptor)
        val binary = encryptedBinary("binary", 100, encryptor)
    }

    class ETest(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<ETest>(TestTable)

        var varchar by TestTable.varchar
        var binary by TestTable.binary
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `encrypted columns with DAO`(testDB: TestDB) {
        withTables(testDB, TestTable) {
            val varcharValue = "varchar"
            val binaryValue = "binary".toByteArray()

            val entity = ETest.new {
                varchar = varcharValue
                binary = binaryValue
            }

            entity.varchar shouldBeEqualTo varcharValue
            entity.binary shouldBeEqualTo binaryValue

            flushCache()
            entityCache.clear()

            ETest.all().first().let {
                it.varchar shouldBeEqualTo varcharValue
                it.binary shouldBeEqualTo binaryValue
            }

            TestTable.selectAll().first().let {
                it[TestTable.varchar] shouldBeEqualTo varcharValue
                it[TestTable.binary] shouldBeEqualTo binaryValue
            }
        }
    }


    @Disabled("Exposed Encryptor는 매번 다른 값으로 암호화하기 때문에, WHERE 절에 쓸 수는 없습니다.")
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find by encrypted value`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 }

        withTables(testDB, TestTable) {
            val varcharValue = "varchar"
            val binaryValue = "binary".toByteArray()

            /**
             * Insert a new record with encrypted values
             *
             * ```sql
             * INSERT INTO TEST ("varchar", "binary")
             * VALUES (UagVRR403hrjcUmKvA3j/Zs43+2UjmcC4XJl7DoaiWuktd2SHYKr, [B@31f295b6)
             * ```
             */

            /**
             * Insert a new record with encrypted values
             *
             * ```sql
             * INSERT INTO TEST ("varchar", "binary")
             * VALUES (UagVRR403hrjcUmKvA3j/Zs43+2UjmcC4XJl7DoaiWuktd2SHYKr, [B@31f295b6)
             * ```
             */
            ETest.new {
                varchar = varcharValue
                binary = binaryValue
            }

            flushCache()
            entityCache.clear()

            // Hibernate 처럼 암호화된 컬럼으로 검색이 불가능합니다.
            ETest.find { TestTable.varchar eq varcharValue }.first().let {
                it.varchar shouldBeEqualTo varcharValue
                it.binary shouldBeEqualTo binaryValue
            }
        }
    }
}
