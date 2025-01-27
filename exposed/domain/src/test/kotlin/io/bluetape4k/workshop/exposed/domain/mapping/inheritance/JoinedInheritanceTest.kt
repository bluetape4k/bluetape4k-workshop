package io.bluetape4k.workshop.exposed.domain.mapping.inheritance

import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.Serializable

/**
 * JPA의 Joined Inheritance 는 Exposed 에서는 Table 간의 Join 으로 구현할 수 있다.
 *
 * 단 CRUD 를 위해서는 따로 Repository 를 만들어야 한다.
 */
class JoinedInheritanceTest: AbstractExposedTest() {

    /**
     * Joined Person Table
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS JOINED_PERSON (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      PERSON_NAME VARCHAR(128) NOT NULL,
     *      SSN VARCHAR(128) NOT NULL
     * )
     * ```
     * ```sql
     * ALTER TABLE JOINED_PERSON ADD CONSTRAINT JOINED_PERSON_PERSON_NAME_SSN_UNIQUE UNIQUE (PERSON_NAME, SSN)
     * ```
     */
    object JoinedPersonTable: IntIdTable("joined_person") {
        val name = varchar("person_name", 128)
        val ssn = varchar("ssn", 128)

        init {
            uniqueIndex(name, ssn)
        }
    }

    /**
     * Joined Employee Table
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS JOINED_EMPLOYEE (
     *      ID INT NOT NULL,
     *      EMP_NO VARCHAR(128) NOT NULL,
     *      MANAGER_ID INT NULL,
     *
     *      CONSTRAINT FK_JOINED_EMPLOYEE_ID__ID FOREIGN KEY (ID)
     *          REFERENCES JOINED_PERSON(ID) ON DELETE CASCADE ON UPDATE CASCADE,
     *
     *      CONSTRAINT FK_JOINED_EMPLOYEE_MANAGER_ID__ID FOREIGN KEY (MANAGER_ID)
     *          REFERENCES JOINED_PERSON(ID) ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object JoinedEmployeeTable: IdTable<Int>("joined_employee") {
        override val id: Column<EntityID<Int>> =
            reference("id", JoinedPersonTable.id, onDelete = CASCADE, onUpdate = CASCADE)

        val empNo = varchar("emp_no", 128)
        val managerId = integer("manager_id").nullable()
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS JOINED_CUSTOMER (
     *      ID INT NOT NULL,
     *      MOBILE VARCHAR(16) NOT NULL,
     *      CONCAT_EMPLOYEE_ID VARCHAR(128) NULL,
     *
     *      CONSTRAINT FK_JOINED_CUSTOMER_ID__ID FOREIGN KEY (ID)
     *          REFERENCES JOINED_PERSON(ID) ON DELETE CASCADE ON UPDATE CASCADE
     * )
     * ```
     */
    object JoinedCustomerTable: IdTable<Int>("joined_customer") {
        override val id: Column<EntityID<Int>> =
            reference("id", JoinedPersonTable.id, onDelete = CASCADE, onUpdate = CASCADE)

        val mobile = varchar("mobile", 16)
        val contactEmployeeId = integer("contact_employee_id").nullable()
    }

    abstract class AbstractJoinedPerson: Serializable {
        abstract val id: Int
        abstract val name: String
        abstract val ssn: String
    }

    data class JoinedEmployee(
        override val id: Int,
        override val name: String,
        override val ssn: String,
        val empNo: String,
        val managerId: Int? = null,
    ): AbstractJoinedPerson()

    data class JoinedCustomer(
        override val id: Int,
        override val name: String,
        override val ssn: String,
        val mobile: String,
        val contactEmployeeId: Int? = null,
    ): AbstractJoinedPerson()


    val joinedEmployees = JoinedPersonTable.innerJoin(JoinedEmployeeTable)
    val joinedCustomers = JoinedPersonTable.innerJoin(JoinedCustomerTable)


    fun Transaction.newEmployee(name: String, ssn: String, empNo: String): JoinedEmployee {
        val personId1 = JoinedPersonTable.insertAndGetId {
            it[JoinedPersonTable.name] = name
            it[JoinedPersonTable.ssn] = ssn
        }
        JoinedEmployeeTable.insert {
            it[id] = personId1
            it[JoinedEmployeeTable.empNo] = empNo
        }

        /**
         * ```sql
         * SELECT JOINED_PERSON.ID,
         *        JOINED_PERSON.PERSON_NAME,
         *        JOINED_PERSON.SSN,
         *        JOINED_EMPLOYEE.ID,
         *        JOINED_EMPLOYEE.EMP_NO
         *   FROM JOINED_PERSON INNER JOIN JOINED_EMPLOYEE ON JOINED_PERSON.ID = JOINED_EMPLOYEE.ID
         *  WHERE JOINED_PERSON.ID = 1
         * ```
         */
        return joinedEmployees
            .selectAll()
            .where { JoinedPersonTable.id eq personId1 }
            .map {
                JoinedEmployee(
                    id = it[JoinedPersonTable.id].value,
                    name = it[JoinedPersonTable.name],
                    ssn = it[JoinedPersonTable.ssn],
                    empNo = it[JoinedEmployeeTable.empNo],
                    managerId = it[JoinedEmployeeTable.managerId]
                )
            }
            .single()
    }

    fun Transaction.newCustomer(name: String, ssn: String, mobile: String, contactEmployeeId: Int?): JoinedCustomer {
        val personId1 = JoinedPersonTable.insertAndGetId {
            it[JoinedPersonTable.name] = name
            it[JoinedPersonTable.ssn] = ssn
        }
        JoinedCustomerTable.insert {
            it[id] = personId1
            it[JoinedCustomerTable.mobile] = mobile
            it[JoinedCustomerTable.contactEmployeeId] = contactEmployeeId
        }

        return joinedCustomers.selectAll()
            .where { JoinedPersonTable.id eq personId1 }
            .map {
                JoinedCustomer(
                    id = it[JoinedPersonTable.id].value,
                    name = it[JoinedPersonTable.name],
                    ssn = it[JoinedPersonTable.ssn],
                    mobile = it[JoinedCustomerTable.mobile],
                    contactEmployeeId = it[JoinedCustomerTable.contactEmployeeId]
                )
            }
            .single()
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inheritance with joined table`(testDB: TestDB) {
        withTables(testDB, JoinedPersonTable, JoinedEmployeeTable, JoinedCustomerTable) {
            val emp1 = newEmployee("Debop", "111111-111111", "EMP-001")
            log.debug { "emp1=$emp1" }

            val emp2 = newEmployee("Kally", "222222-222222", "EMP-002")
            log.debug { "emp2=$emp2" }

            val customer = newCustomer("Black", "333333-333333", "010-5555-5555", emp2.id)


            // TODO: 삭제 등 추가적인 테스트 코드를 작성하세요.
        }
    }
}
