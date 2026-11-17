package io.bluetape4k.workshop.chaos.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.chaos.model.Student
import io.bluetape4k.workshop.chaos.model.StudentTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository

@Repository
class StudentJdbcRepository(
    private val database: Database,
) {

    companion object: KLogging()

    fun findAll(): List<Student> = transaction(database) {
        StudentTable.selectAll().map { it.toStudent() }
    }

    fun findById(id: Int): Student? {
        val studentId = id.requirePositiveNumber("id")
        return transaction(database) {
            StudentTable
                .selectAll()
                .where { StudentTable.id eq studentId }
                .firstOrNull()
                ?.toStudent()
        }
    }

    fun deleteById(id: Int): Int {
        val studentId = id.requirePositiveNumber("id")
        return transaction(database) {
            StudentTable.deleteWhere { StudentTable.id eq studentId }
        }
    }

    fun insert(student: Student): Int {
        val studentId = student.id!!.requirePositiveNumber("student.id")
        val name = student.name.requireNotBlank("student.name")
        val passportNumber = student.passportNumber.requireNotBlank("student.passportNumber")

        return transaction(database) {
            StudentTable.insert {
                it[StudentTable.id] = studentId
                it[StudentTable.name] = name
                it[StudentTable.passportNumber] = passportNumber
            }.let { 1 }
        }
    }

    fun update(student: Student): Int {
        val studentId = student.id!!.requirePositiveNumber("student.id")
        val name = student.name.requireNotBlank("student.name")
        val passportNumber = student.passportNumber.requireNotBlank("student.passportNumber")

        return transaction(database) {
            StudentTable.update({ StudentTable.id eq studentId }) {
                it[StudentTable.name] = name
                it[StudentTable.passportNumber] = passportNumber
            }
        }
    }

    private fun ResultRow.toStudent(): Student = Student(
        id = this[StudentTable.id],
        name = this[StudentTable.name],
        passportNumber = this[StudentTable.passportNumber],
    )
}
