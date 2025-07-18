package io.bluetape4k.workshop.exposed.sql.crypt

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.crypt.Algorithms
import org.jetbrains.exposed.v1.crypt.encryptedBinary
import org.jetbrains.exposed.v1.crypt.encryptedVarchar
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EncryptedColumnDaoTest: AbstractExposedTest() {

    companion object: KLogging() {
        private val encryptor = Algorithms.AES_256_PBE_GCM("passwd", "12345678")
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS test (
     *      id SERIAL PRIMARY KEY,
     *      "varchar" VARCHAR(100) NOT NULL,
     *      "binary" bytea NOT NULL
     * )
     * ```
     */
    object TestTable: IntIdTable() {
        val varchar = encryptedVarchar("varchar", 100, encryptor)
        val binary = encryptedBinary("binary", 100, encryptor)
    }

    class ETest(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<ETest>(TestTable)

        var varchar by TestTable.varchar
        var binary by TestTable.binary

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("varchar", varchar)
                .add("binary", binary.contentToString())
                .toString()
    }

    /**
     * Create a new record with encrypted values
     *
     * ```sql
     * INSERT INTO TEST ("varchar", "binary")
     * VALUES (bIPLXMhvfla/2yj0pA6nVN+xaLtVVDSUzncKmU2nNAcZQWqouIes, [B@546394ed)
     * ```
     */
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

            entityCache.clear()

            entity.varchar shouldBeEqualTo varcharValue
            entity.binary shouldBeEqualTo binaryValue

            // Entity를 통해 조회
            ETest.all().first().let {
                it.varchar shouldBeEqualTo varcharValue
                it.binary shouldBeEqualTo binaryValue
            }

            // DSL을 통해 조회
            TestTable.selectAll().first().let {
                it[TestTable.varchar] shouldBeEqualTo varcharValue
                it[TestTable.binary] shouldBeEqualTo binaryValue
            }
        }
    }

    /**
     * find by encrypted value
     *
     * Exposed Encryptor는 매번 다른 값으로 암호화하기 때문에, WHERE 절에 쓸 수는 없습니다.
     * Jasypt 를 사용해서 매번 같은 값으로 암호화를 하도록 하면 가능합니다.
     *
     * ```sql
     * INSERT INTO TEST ("varchar", "binary")
     * VALUES (UagVRR403hrjcUmKvA3j/Zs43+2UjmcC4XJl7DoaiWuktd2SHYKr, [B@31f295b6)
     * ```
     */
    @Disabled("Exposed Encryptor는 매번 다른 값으로 암호화하기 때문에, WHERE 절에 쓸 수는 없습니다.")
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find by encrypted value`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 }

        withTables(testDB, TestTable) {
            val varcharValue = "varchar"
            val binaryValue = "binary".toByteArray()

            ETest.new {
                varchar = varcharValue
                binary = binaryValue
            }

            entityCache.clear()

            // Hibernate 처럼 암호화된 컬럼으로 검색이 불가능합니다.
            ETest.find { TestTable.varchar eq varcharValue }.first().let {
                it.varchar shouldBeEqualTo varcharValue
                it.binary shouldBeEqualTo binaryValue
            }
        }
    }
}
