package io.bluetape4k.workshop.exposed.domain.mapping.entities

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.flushCache
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate

class EmbeddedClassTest: AbstractExposedTest() {

    val today = LocalDate.now()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create embedded class`(testDB: TestDB) {
        withTables(testDB, TaskTable) {
            val task = TaskEntity.new {
                status = TaskStatusType.TO_DO
                changedOn = today
                changedBy = "admin"
            }

            flushCache()
            task.refresh(true)

            val loadedTask = TaskEntity.findById(task.id.value)!!

            loadedTask.status shouldBeEqualTo TaskStatusType.TO_DO
            loadedTask.changedOn shouldBeEqualTo today
            loadedTask.changedBy shouldBeEqualTo "admin"
        }
    }


}
