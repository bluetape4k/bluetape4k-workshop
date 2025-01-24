package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.collections.size
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.BankSchema.AccountOwner
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.BankSchema.BankAccount
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.BankSchema.OwnerAccountMapTable
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class BankSchemaTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `many-to-many manipulation by owner`(testDB: TestDB) {
        withTables(testDB, *BankSchema.allTables) {
            val owner1 = AccountOwner.new { ssn = faker.idNumber().ssnValid() }
            val owner2 = AccountOwner.new { ssn = faker.idNumber().ssnValid() }

            val account1 = BankAccount.new { number = faker.finance().creditCard() }
            val account2 = BankAccount.new { number = faker.finance().creditCard() }
            val account3 = BankAccount.new { number = faker.finance().creditCard() }
            val account4 = BankAccount.new { number = faker.finance().creditCard() }

            OwnerAccountMapTable.insert {
                it[ownerId] = owner1.id
                it[accountId] = account1.id
            }
            OwnerAccountMapTable.insert {
                it[ownerId] = owner1.id
                it[accountId] = account2.id
            }
            OwnerAccountMapTable.insert {
                it[ownerId] = owner2.id
                it[accountId] = account1.id
            }
            OwnerAccountMapTable.insert {
                it[ownerId] = owner2.id
                it[accountId] = account3.id
            }
            OwnerAccountMapTable.insert {
                it[ownerId] = owner2.id
                it[accountId] = account4.id
            }

            flushCache()
            entityCache.clear()

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM BANK_ACCOUNT INNER JOIN OWNER_ACCOUNT_MAP ON BANK_ACCOUNT.ID = OWNER_ACCOUNT_MAP.ACCOUNT_ID
             *  WHERE OWNER_ACCOUNT_MAP.OWNER_ID = 1
             * ```
             * ```sql
             * SELECT BANK_ACCOUNT.ID,
             *        BANK_ACCOUNT."number",
             *        OWNER_ACCOUNT_MAP.ACCOUNT_ID,
             *        OWNER_ACCOUNT_MAP.OWNER_ID
             *   FROM BANK_ACCOUNT INNER JOIN OWNER_ACCOUNT_MAP ON BANK_ACCOUNT.ID = OWNER_ACCOUNT_MAP.ACCOUNT_ID
             *  WHERE OWNER_ACCOUNT_MAP.OWNER_ID = 1
             * ```
             */
            var loadedOwner1 = AccountOwner.findById(owner1.id)!!
            loadedOwner1.accounts.count().toInt() shouldBeEqualTo 2
            loadedOwner1.accounts shouldContainSame listOf(account1, account2)

            var loadedOwner2 = AccountOwner.findById(owner2.id)!!
            loadedOwner2.accounts.count().toInt() shouldBeEqualTo owner2.accounts.size()
            loadedOwner2.accounts shouldContainSame listOf(account1, account3, account4)

            /**
             * ```sql
             * DELETE FROM OWNER_ACCOUNT_MAP
             *  WHERE (OWNER_ACCOUNT_MAP.OWNER_ID = 2)
             *    AND (OWNER_ACCOUNT_MAP.ACCOUNT_ID = 3)
             * ```
             */
            OwnerAccountMapTable.deleteWhere {
                (ownerId eq owner2.id) and (accountId eq account3.id)
            }

            entityCache.clear()

            loadedOwner1 = AccountOwner.findById(owner1.id)!!
            loadedOwner1.accounts.count().toInt() shouldBeEqualTo owner1.accounts.size()
            loadedOwner1.accounts shouldContainSame listOf(account1, account2)

            loadedOwner2 = AccountOwner.findById(owner2.id)!!
            loadedOwner2.accounts.count().toInt() shouldBeEqualTo owner2.accounts.size()
            loadedOwner2.accounts shouldContainSame listOf(account1, account4)

            // cascade 에 REMOVE 가 빠져 있다면, many-to-many 관계만 삭제된다.
            /**
             * ```sql
             * DELETE FROM OWNER_ACCOUNT_MAP WHERE OWNER_ACCOUNT_MAP.OWNER_ID = 2
             * ```
             * ```sql
             * DELETE FROM ACCOUNT_OWNER WHERE ACCOUNT_OWNER.ID = 2
             * ```
             */
            OwnerAccountMapTable.deleteWhere { ownerId eq loadedOwner2.id }
            loadedOwner2.delete()

            entityCache.clear()

            val removedAccount = BankAccount.findById(account3.id)!!
            removedAccount.owners.count() shouldBeEqualTo 0L
        }
    }
}
