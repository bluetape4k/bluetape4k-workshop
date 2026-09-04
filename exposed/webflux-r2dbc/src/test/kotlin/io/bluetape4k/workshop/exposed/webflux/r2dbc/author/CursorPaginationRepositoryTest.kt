package io.bluetape4k.workshop.exposed.webflux.r2dbc.author

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.core.ExposedCursorPage
import io.bluetape4k.exposed.r2dbc.repository.LongR2dbcRepository
import io.bluetape4k.exposed.r2dbc.repository.findCursorPage
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Test
import java.util.UUID

private object CursorPaginationTable : IdTable<Long>("workshop_cursor_pagination_rows") {
    override val id: Column<EntityID<Long>> = long("id").entityId()
    override val primaryKey = PrimaryKey(id)
    val active = bool("active")
}

private data class CursorRecord(val id: Long, val active: Boolean)

private object CursorPaginationRepository : LongR2dbcRepository<CursorRecord> {
    override val table = CursorPaginationTable

    override fun extractId(entity: CursorRecord): Long = entity.id

    override suspend fun ResultRow.toEntity(): CursorRecord = CursorRecord(
        id = this[CursorPaginationTable.id].value,
        active = this[CursorPaginationTable.active],
    )

    suspend fun insert(record: CursorRecord) {
        CursorPaginationTable.insert {
            it[CursorPaginationTable.id] = record.id
            it[CursorPaginationTable.active] = record.active
        }
    }
}

private class BlockingCursorPaginationRepository(
    private val mapperEntered: CompletableDeferred<Unit>,
    private val releaseMapper: CompletableDeferred<Unit>,
) : LongR2dbcRepository<CursorRecord> {
    override val table = CursorPaginationTable

    override fun extractId(entity: CursorRecord): Long = entity.id

    override suspend fun ResultRow.toEntity(): CursorRecord {
        mapperEntered.complete(Unit)
        releaseMapper.await()
        return CursorRecord(
            id = this[CursorPaginationTable.id].value,
            active = this[CursorPaginationTable.active],
        )
    }
}

class CursorPaginationRepositoryTest {

    // sparse ID 경계를 페이지 요청 사이에서 의도적으로 변경합니다.
    @Test
    fun `suspend cursor page follows sparse IDs across insert and delete boundaries`() = runSuspendIO {
        val database = database("boundary")
        try {
            val firstCursor = suspendTransaction(db = database) {
                SchemaUtils.create(CursorPaginationTable)
                seed(CursorRecord(1, true), CursorRecord(3, true), CursorRecord(7, true), CursorRecord(20, true))
                val page = CursorPaginationRepository.findCursorPage(pageSize = 2)
                commit()
                page.nextCursor
            }

            suspendTransaction(db = database) {
                CursorPaginationTable.deleteWhere { CursorPaginationTable.id eq 3L }
                CursorPaginationRepository.insert(CursorRecord(2, true))
                CursorPaginationRepository.insert(CursorRecord(5, true))
                commit()
            }

            val nextPage = suspendTransaction(db = database) {
                val page = CursorPaginationRepository.findCursorPage(pageSize = 2, cursor = firstCursor)
                commit()
                page
            }

            nextPage.content.map(CursorRecord::id) shouldBeEqualTo listOf(5L, 7L)
            nextPage.nextCursor shouldBeEqualTo 7L
            nextPage.hasNext.shouldBeTrue()
        } finally {
            suspendTransaction(db = database) {
                SchemaUtils.drop(CursorPaginationTable)
                commit()
            }
        }
    }

    @Test
    fun `suspend cursor mapper cancellation returns a size one pool connection`() = runSuspendIO {
        val database = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///workshop-cursor-cancel-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
        )
        val mapperEntered = CompletableDeferred<Unit>()
        val releaseMapper = CompletableDeferred<Unit>()
        val blockingRepository = BlockingCursorPaginationRepository(mapperEntered, releaseMapper)

        try {
            suspendTransaction(db = database) {
                SchemaUtils.create(CursorPaginationTable)
                CursorPaginationRepository.insert(CursorRecord(1, true))
                CursorPaginationRepository.insert(CursorRecord(3, true))
                commit()
            }

            val request = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async(
                start = CoroutineStart.UNDISPATCHED,
            ) {
                suspendTransaction(db = database) {
                    CursorPaginationRepository.insert(CursorRecord(99, true))
                    blockingRepository.findCursorPage(pageSize = 2)
                }
            }

            mapperEntered.await()
            request.cancel()
            assertFailsWith<CancellationException> { request.await() }

            val idsAfterCancellation = withTimeout(5_000L) {
                suspendTransaction(db = database) {
                    val ids = CursorPaginationRepository.findCursorPage(pageSize = 100).content
                        .map(CursorRecord::id)
                    commit()
                    ids
                }
            }
            idsAfterCancellation shouldBeEqualTo listOf(1L, 3L)
        } finally {
            releaseMapper.complete(Unit)
            runCatching {
                suspendTransaction(db = database) {
                    SchemaUtils.drop(CursorPaginationTable)
                    commit()
                }
            }
        }
    }

    @Test
    fun `suspend cursor page size uses upstream bounds`() = runSuspendIO {
        val database = database("size")
        try {
            suspendTransaction(db = database) {
                SchemaUtils.create(CursorPaginationTable)
                assertFailsWith<IllegalArgumentException> {
                    CursorPaginationRepository.findCursorPage(pageSize = 0)
                }
                assertFailsWith<IllegalArgumentException> {
                    CursorPaginationRepository.findCursorPage(pageSize = 10_001)
                }
                commit()
            }
        } finally {
            suspendTransaction(db = database) {
                SchemaUtils.drop(CursorPaginationTable)
                commit()
            }
        }
    }

    private suspend fun org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction.seed(
        vararg records: CursorRecord,
    ) {
        records.forEach { CursorPaginationRepository.insert(it) }
    }

    private fun database(label: String): R2dbcDatabase = R2dbcDatabase.connect(
        url = "r2dbc:h2:mem:///workshop-cursor-$label-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
        databaseConfig = R2dbcDatabaseConfig.Builder().apply { setUrl("r2dbc:h2:mem:///workshop-cursor-$label") },
    )
}
