package io.bluetape4k.workshop.chaos.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.chaos.model.Student
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class StudentJdbcRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    companion object : KLogging()

    class StudentRowMapper : RowMapper<Student> {
        override fun mapRow(rs: ResultSet, rowNum: Int): Student {
            return Student(
                id = rs.getInt("id"),
                name = rs.getString("name"),
                passportNumber = rs.getString("passport_number"),
            )
        }
    }

    fun findAll(): List<Student> {
        return jdbcTemplate.query("select * from student", StudentRowMapper())
    }

    fun findById(id: Int): Student? {
        val studentId = id.requirePositiveNumber("id")
        return jdbcTemplate.query(
            "select * from student where id=?",
            StudentRowMapper(),
            studentId,
        ).firstOrNull()
    }

    fun deleteById(id: Int): Int {
        val studentId = id.requirePositiveNumber("id")
        return jdbcTemplate.update("delete from student where id=?", studentId)
    }

    fun insert(student: Student): Int {
        val studentId = student.id.requireNotNull("student.id").requirePositiveNumber("student.id")
        val name = student.name.requireNotNull("student.name").requireNotBlank("student.name")
        val passportNumber = student.passportNumber
            .requireNotNull("student.passportNumber")
            .requireNotBlank("student.passportNumber")

        return jdbcTemplate.update(
            "insert into Student (id, name, passport_number) values(?, ?, ?)",
            studentId,
            name,
            passportNumber,
        )
    }

    fun update(student: Student): Int {
        val studentId = student.id.requireNotNull("student.id").requirePositiveNumber("student.id")
        val name = student.name.requireNotNull("student.name").requireNotBlank("student.name")
        val passportNumber = student.passportNumber
            .requireNotNull("student.passportNumber")
            .requireNotBlank("student.passportNumber")

        return jdbcTemplate.update(
            "update student set name=?, passport_number=? where id=?",
            name,
            passportNumber,
            studentId,
        )
    }
}
