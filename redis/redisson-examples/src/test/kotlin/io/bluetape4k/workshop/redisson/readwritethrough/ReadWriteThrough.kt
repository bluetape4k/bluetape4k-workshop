package io.bluetape4k.workshop.redisson.readwritethrough

import io.bluetape4k.jdbc.sql.extract
import io.bluetape4k.jdbc.sql.runQuery
import io.bluetape4k.jdbc.sql.withConnect
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.redisson.api.MapOptions
import org.redisson.api.map.MapLoader
import org.redisson.api.map.MapWriter
import org.redisson.api.map.RetryableMapWriter
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

object ReadWriteThrough : KLogging() {

    const val SELECT_ACTORS = "SELECT * FROM Actors"
    const val SELECT_ACTOR_IDS = "SELECT id FROM Actors"
    const val SELECT_ACTOR_BY_ID = "SELECT * FROM Actors WHERE id=?"
    const val INSERT_ACTOR = "INSERT INTO Actors(id, firstname, lastname) VALUES(?, ?, ?)"
    const val DELETE_ACTOR = "DELETE FROM Actors WHERE id=?"

    const val SELECT_ACTOR_COUNT = "SELECT count(*) as cnt FROM Actors"

    /**
     * [Actor] 를 Redisson Map ([org.redisson.api.map.RMap]) 에서 사용하기 위한 MapLoader 와 MapWriter 를 생성합니다.
     *
     * @see [org.redisson.api.map.MapLoaderAsync]
     *
     * @param dataSource
     * @return
     */
    fun actorMapLoader(dataSource: DataSource): MapLoader<Int, Actor> {
        return object : MapLoader<Int, Actor> {
            override fun load(key: Int): Actor? {
                log.debug { "Load actor from DB. actor id=$key" }

                return dataSource
                    .withConnect { conn ->
                        conn.prepareStatement(SELECT_ACTOR_BY_ID).use { ps ->
                            ps.setInt(1, key)
                            val rs: ResultSet = ps.executeQuery()

                            rs.extract {
                                Actor(
                                    int[Actor::id.name],
                                    string[Actor::firstname.name],
                                    string[Actor::lastname.name]
                                )
                            }
                        }
                    }
                    .firstOrNull()
            }

            override fun loadAllKeys(): MutableIterable<Int> {
                    // read-through loading에는 모든 key가 필요합니다.
                log.debug { "Load all actor ids." }

                return dataSource.runQuery(SELECT_ACTOR_IDS) { rs ->
                    // Redisson이 모든 key를 소비하지 않을 수 있으므로 JDBC helper가 지원하면 extraction을 lazy하게 유지합니다.
                    rs.extract {
                        int[Actor::id.name]
                    }
                }.toMutableList()
            }
        }
    }

    /**
     * [Actor]를 DB에 기록하는 Redisson map writer입니다.
     *
     * @see [org.redisson.api.map.RetryableMapWriter]
     * @see [org.redisson.api.map.RetryableMapWriterAsync]
     *
     * @param dataSource Actor row를 쓰는 JDBC data source입니다.
     * @return retry 설정이 적용된 [MapWriter]입니다.
     */
    fun actorMapWriter(dataSource: DataSource): MapWriter<Int, Actor> {

        val mapWriter = object : MapWriter<Int, Actor> {
            override fun write(map: MutableMap<Int, Actor>) {
                log.debug { "Write actor to DB. actors=${map.values.joinToString()}" }

                dataSource.withConnect { conn ->
                    conn.prepareStatement(INSERT_ACTOR).use { ps ->
                        map.forEach { (id, actor) ->
                            ps.setInt(1, id)
                            ps.setString(2, actor.firstname)
                            ps.setString(3, actor.lastname)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }

            }

            override fun delete(keys: MutableCollection<Int>?) {
            }
        }

        // 참고: 여기서는 Redisson RetryableMapWriter로 충분하며, Resilience4j Retry도 가능한 wrapper입니다.
        val options = MapOptions.defaults<Int, Actor>()
            .writerRetryAttempts(3)
            .writerRetryInterval(100.milliseconds.toJavaDuration())

        return RetryableMapWriter(options, mapWriter)
    }
}
