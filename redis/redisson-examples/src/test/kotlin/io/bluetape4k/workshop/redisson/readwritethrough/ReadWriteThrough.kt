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

object ReadWriteThrough: KLogging() {

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
        return object: MapLoader<Int, Actor> {
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
                // Read through 를 하기 위해서는 모든 key 를 읽어와야 합니다.
                log.debug { "Load all actor ids." }

                return dataSource.runQuery(SELECT_ACTOR_IDS) { rs ->
                    // TODO: extract 시에 꼭 Sequence 나 Iterable 로 해야 한다. 끝까지 읽지 않는 작업이 있을 수 있다
                    // loadAllKeys 도 모두 읽는 게 아니라, Map의 제한만큼만 읽어야 하기 때문이다.
                    rs.extract {
                        int[Actor::id.name]
                    }
                }.toMutableList()
            }
        }
    }

    /**
     * Actor map writer
     *
     * @see [org.redisson.api.map.RetryableMapWriter]
     * @see [org.redisson.api.map.RetryableMapWriterAsync]
     *
     * @param dataSource
     * @return
     */
    fun actorMapWriter(dataSource: DataSource): MapWriter<Int, Actor> {

        val mapWriter = object: MapWriter<Int, Actor> {
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

        // NOTE: Redisson의 RetryableMapWriter 를 사용해도 되고, Resilience4j Retry 를 사용해도 된다.
        val options = MapOptions.defaults<Int, Actor>()
            .writerRetryAttempts(3)
            .writerRetryInterval(100.milliseconds.toJavaDuration())

        return RetryableMapWriter(options, mapWriter)
    }
}
