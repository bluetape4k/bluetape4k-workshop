package io.bluetape4k.workshop.exposed.domain.mapping.associations.manytomany

import io.bluetape4k.collections.size
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytomany.BankSchema.AccountOwner
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytomany.BankSchema.BankAccount
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytomany.BankSchema.OwnerAccountMapTable
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
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
             * DELETE
             *   FROM owner_account_map
             *  WHERE (owner_account_map.owner_id = 2)
             *    AND (owner_account_map.account_id = 3)
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

            /**
             * cascade 에 REMOVE 가 빠져 있다면, many-to-many 관계만 삭제된다.
             *
             * ```sql
             * DELETE FROM owner_account_map WHERE owner_account_map.owner_id = 2
             * ```
             * ```sql
             * DELETE FROM account_owner WHERE account_owner.id = 2
             * ```
             */
            OwnerAccountMapTable.deleteWhere { ownerId eq loadedOwner2.id }
            loadedOwner2.delete()

            entityCache.clear()

            val removedAccount = BankAccount.findById(account3.id)!!

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM account_owner INNER JOIN owner_account_map
             *      ON account_owner.id = owner_account_map.owner_id
             *  WHERE owner_account_map.account_id = 3
             * ```
             */
            removedAccount.owners.count() shouldBeEqualTo 0L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `many-to-many manipulation by account`(testDB: TestDB) {
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

            account1.assertAccountExists()
            account2.assertAccountExists()
            account3.assertAccountExists()
            account4.assertAccountExists()

            flushCache()
            entityCache.clear()

            /**
             * Delete mapping (accountId = 3, ownerId = 2)
             *
             * ```sql
             * DELETE
             *   FROM owner_account_map
             *  WHERE (owner_account_map.account_id = 3)
             *    AND (owner_account_map.owner_id = 2)
             * ```
             */
            OwnerAccountMapTable.deleteWhere { (accountId eq account3.id) and (ownerId eq owner2.id) }

            /**
             * ```sql
             * SELECT account_owner.id, account_owner.ssn
             *   FROM account_owner
             *  WHERE account_owner.id = 2;
             *
             * SELECT COUNT(*)
             *   FROM bank_account INNER JOIN owner_account_map
             *      ON bank_account.id = owner_account_map.account_id
             *  WHERE owner_account_map.owner_id = 2
             * ```
             */
            val loaded = AccountOwner.findById(owner2.id)!!
            loaded.accounts.count().toInt() shouldBeEqualTo 2
        }
    }

    private fun BankAccount.assertAccountExists() {
        val account = this
        val loaded = BankAccount.findById(account.id)!!
        loaded shouldBeEqualTo account
        loaded.owners.count().toInt() shouldBeEqualTo account.owners.size()
    }
}
