package io.bluetape4k.workshop.exposed.mvc.jdbc.author

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.exposed.jdbc.repository.findCursorPage
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test

private object CursorPaginationTable : IdTable<Long>("workshop_jdbc_cursor_pagination_rows") {
    override val id: Column<EntityID<Long>> = long("id").entityId()
    override val primaryKey = PrimaryKey(id)
    val active = bool("active")
}

private data class CursorRecord(val id: Long, val active: Boolean)

private object CursorPaginationRepository : LongJdbcRepository<CursorRecord> {
    override val table = CursorPaginationTable

    override fun extractId(entity: CursorRecord): Long = entity.id

    override fun ResultRow.toEntity(): CursorRecord = CursorRecord(
        id = this[CursorPaginationTable.id].value,
        active = this[CursorPaginationTable.active],
    )

    fun insert(record: CursorRecord) {
        CursorPaginationTable.insert {
            it[CursorPaginationTable.id] = record.id
            it[CursorPaginationTable.active] = record.active
        }
    }
}

class CursorPaginationRepositoryTest {

    // sparse ID 경계를 페이지 요청 사이에서 의도적으로 변경합니다.
    @Test
    fun `cursor page follows sparse IDs across insert and delete boundaries`() =
        withTables(TestDB.POSTGRESQL, CursorPaginationTable) {
            val firstCursor = run {
                seed(CursorRecord(1, true), CursorRecord(3, true), CursorRecord(7, true), CursorRecord(20, true))
                CursorPaginationRepository.findCursorPage(pageSize = 2).nextCursor
            }

            CursorPaginationTable.deleteWhere { CursorPaginationTable.id eq 3L }
            CursorPaginationRepository.insert(CursorRecord(2, true))
            CursorPaginationRepository.insert(CursorRecord(5, true))

            val nextPage = CursorPaginationRepository.findCursorPage(pageSize = 2, cursor = firstCursor)

            nextPage.content.map(CursorRecord::id) shouldBeEqualTo listOf(5L, 7L)
            nextPage.nextCursor shouldBeEqualTo 7L
            nextPage.hasNext.shouldBeTrue()
        }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.seed(vararg records: CursorRecord) {
        records.forEach { CursorPaginationRepository.insert(it) }
    }
}
