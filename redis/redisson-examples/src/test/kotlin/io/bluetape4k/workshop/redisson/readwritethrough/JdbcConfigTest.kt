package io.bluetape4k.workshop.redisson.readwritethrough

import io.bluetape4k.jdbc.sql.extract
import io.bluetape4k.jdbc.sql.runQuery
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.annotation.Transactional
import javax.sql.DataSource

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [JdbcConfig::class])
@Transactional
class JdbcConfigTest {

    companion object: KLoggingChannel() {
        private const val SELECT_ACTORS = "SELECT * FROM Actors"
    }

    @Autowired
    private val dataSource: DataSource = uninitialized()

    @Test
    fun `context loading`() {
        dataSource.shouldNotBeNull()
    }

    @Test
    fun `get all actors`() {
        val actors = dataSource.runQuery(SELECT_ACTORS) { rs ->
            rs.extract {
                Actor(
                    int[Actor::id.name]!!,
                    string[Actor::firstname.name]!!,
                    string[Actor::lastname.name]!!,
                )
            }
        }
        actors.shouldNotBeEmpty()
        actors.forEachIndexed { index, actor ->
            log.debug { "Actor[$index]=$actor" }
        }
    }
}
