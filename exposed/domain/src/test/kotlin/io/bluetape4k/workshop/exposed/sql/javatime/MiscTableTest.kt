package io.bluetape4k.workshop.exposed.sql.javatime

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.MiscTable
import io.bluetape4k.workshop.exposed.domain.shared.MiscTable.E
import io.bluetape4k.workshop.exposed.domain.shared.MiscTable.E.ONE
import io.bluetape4k.workshop.exposed.domain.shared.checkInsert
import io.bluetape4k.workshop.exposed.domain.shared.checkRow
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.substring
import org.jetbrains.exposed.v1.javatime.JavaLocalDateTimeColumnType
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.dateTimeParam
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.javatime.duration
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.assertNull

/**
 * Postgres:
 * ```sql
 * CREATE TABLE IF NOT EXISTS misc (
 *      "by" SMALLINT NOT NULL,
 *      byn SMALLINT NULL,
 *      sm SMALLINT NOT NULL,
 *      smn SMALLINT NULL,
 *      n INT NOT NULL,
 *      nn INT NULL,
 *      e INT NOT NULL,
 *      en INT NULL,
 *      es VARCHAR(5) NOT NULL,
 *      esn VARCHAR(5) NULL,
 *      "c" VARCHAR(4) NOT NULL,
 *      cn VARCHAR(4) NULL,
 *      s VARCHAR(100) NOT NULL,
 *      sn VARCHAR(100) NULL,
 *      dc DECIMAL(12, 2) NOT NULL,
 *      dcn DECIMAL(12, 2) NULL,
 *      fcn REAL NULL,
 *      dblcn DOUBLE PRECISION NULL,
 *      "char" CHAR NULL,
 *      d DATE NOT NULL,
 *      dn DATE NULL,
 *      t TIME NOT NULL,
 *      tn TIME NULL,
 *      dt TIMESTAMP NOT NULL,
 *      dtn TIMESTAMP NULL,
 *      ts TIMESTAMP NOT NULL,
 *      tsn TIMESTAMP NULL,
 *      dr BIGINT NOT NULL,
 *      drn BIGINT NULL,
 *
 *      CONSTRAINT chk_Misc_signed_byte_by CHECK ("by" BETWEEN -128 AND 127),
 *      CONSTRAINT chk_Misc_signed_byte_byn CHECK (byn BETWEEN -128 AND 127)
 * );
 * ```
 */
object Misc: MiscTable() {
    val d = date("d")
    val dn = date("dn").nullable()

    val t = time("t")
    val tn = time("tn").nullable()

    val dt = datetime("dt")
    val dtn = datetime("dtn").nullable()

    val ts = timestamp("ts")
    val tsn = timestamp("tsn").nullable()

    val dr = duration("dr")
    val drn = duration("drn").nullable()
}

class MiscTableTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert 01`(testDB: TestDB) {
        val tbl = Misc
        val date = today
        val time = LocalTime.now()
        val dateTime = LocalDateTime.now()
        val timestamp = Instant.now()
        val duration = Duration.ofMinutes(1)

        withTables(testDB, tbl) {
            tbl.insert {
                it[by] = 13
                it[sm] = -10
                it[n] = 42
                it[d] = date
                it[t] = time
                it[dt] = dateTime
                it[ts] = timestamp
                it[dr] = duration
                it[e] = ONE
                it[es] = ONE
                it[c] = "test"
                it[s] = "test"
                it[dc] = BigDecimal("239.42")
                it[char] = '('
            }

            val row = tbl.selectAll().single()
            tbl.checkRow(
                row, 13, null, -10, null, 42, null, ONE, null, ONE,
                null, "test", null, "test", null, BigDecimal("239.42"), null, null, null
            )

            tbl.checkRowDates(row, date, null, time, null, dateTime, null, timestamp, null, duration, null)
            row[tbl.char] shouldBeEqualTo '('
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert 02`(testDB: TestDB) {
        val tbl = Misc
        val date = today
        val time = LocalTime.now()
        val dateTime = LocalDateTime.now()
        val timestamp = Instant.now()
        val duration = Duration.ofMinutes(1)

        withTables(testDB, tbl) {
            tbl.insert {
                it[by] = 13
                it[byn] = null
                it[sm] = -10
                it[smn] = null
                it[n] = 42
                it[nn] = null
                it[d] = date
                it[dn] = null
                it[t] = time
                it[tn] = null
                it[dt] = dateTime
                it[dtn] = null
                it[ts] = timestamp
                it[tsn] = null
                it[dr] = duration
                it[drn] = null
                it[e] = ONE
                it[en] = null
                it[es] = ONE
                it[esn] = null
                it[c] = "test"
                it[cn] = null
                it[s] = "test"
                it[sn] = null
                it[dc] = BigDecimal("239.42")
                it[dcn] = null
                it[fcn] = null
            }

            val row = tbl.selectAll().single()
            tbl.checkRow(
                row, 13, null, -10, null, 42, null, ONE, null, ONE,
                null, "test", null, "test", null, BigDecimal("239.42"), null, null, null
            )
            tbl.checkRowDates(row, date, null, time, null, dateTime, null, timestamp, null, duration, null)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert 03`(testDB: TestDB) {
        val tbl = Misc
        val date = today
        val time = LocalTime.now()
        val dateTime = LocalDateTime.now()
        val timestamp = Instant.now()
        val duration = Duration.ofMinutes(1)

        withTables(testDB, tbl) {
            tbl.insert {
                it[by] = 13
                it[byn] = 13
                it[sm] = -10
                it[smn] = -10
                it[n] = 42
                it[nn] = 42
                it[d] = date
                it[dn] = date
                it[t] = time
                it[tn] = time
                it[dt] = dateTime
                it[dtn] = dateTime
                it[ts] = timestamp
                it[tsn] = timestamp
                it[dr] = duration
                it[drn] = duration
                it[e] = ONE
                it[en] = ONE
                it[es] = ONE
                it[esn] = ONE
                it[c] = "test"
                it[cn] = "test"
                it[s] = "test"
                it[sn] = "test"
                it[dc] = BigDecimal("239.42")
                it[dcn] = BigDecimal("239.42")
                it[fcn] = 239.42f
                it[dblcn] = 567.89
            }

            val row = tbl.selectAll().single()
            tbl.checkRow(
                row, 13, 13, -10, -10, 42, 42, ONE, ONE, ONE, ONE,
                "test", "test", "test", "test", BigDecimal("239.42"), BigDecimal("239.42"), 239.42f, 567.89
            )
            tbl.checkRowDates(row, date, date, time, time, dateTime, dateTime, timestamp, timestamp, duration, duration)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert 04`(testDB: TestDB) {
        val shortStringThatNeedsEscaping = "A'br"
        val stringThatNeedsEscaping = "A'braham Barakhyahu"
        val tbl = Misc
        val date = today
        val time = LocalTime.now()
        val dateTime = LocalDateTime.now()
        val timestamp = Instant.now()
        val duration = Duration.ofMinutes(1)

        withTables(testDB, tbl) {
            tbl.insert {
                it[by] = 13
                it[sm] = -10
                it[n] = 42
                it[d] = date
                it[t] = time
                it[dt] = dateTime
                it[ts] = timestamp
                it[dr] = duration
                it[e] = ONE
                it[es] = ONE
                it[c] = shortStringThatNeedsEscaping
                it[s] = stringThatNeedsEscaping
                it[dc] = BigDecimal("239.42")
            }

            val row = tbl.selectAll().single()
            tbl.checkRow(
                row, 13, null, -10, null, 42, null, ONE, null, ONE, null,
                shortStringThatNeedsEscaping, null, stringThatNeedsEscaping, null,
                BigDecimal("239.42"), null, null, null
            )
            tbl.checkRowDates(row, date, null, time, null, dateTime, null, timestamp, null, duration, null)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and get`(testDB: TestDB) {
        val tbl = Misc
        val date = today
        val time = LocalTime.now()
        val dateTime = LocalDateTime.now()
        val timestamp = Instant.now()
        val duration = Duration.ofMinutes(1)

        withTables(testDB, tbl) {
            val row = tbl.insert {
                it[by] = 13
                it[sm] = -10
                it[n] = 42
                it[d] = date
                it[t] = time
                it[dt] = dateTime
                it[ts] = timestamp
                it[dr] = duration
                it[e] = ONE
                it[es] = ONE
                it[c] = "test"
                it[s] = "test"
                it[dc] = BigDecimal("239.42")
                it[char] = '('
            }

            tbl.checkInsert(
                row, 13, null, -10, null, 42, null, ONE, null, ONE,
                null, "test", null, BigDecimal("239.42"), null, null, null
            )
            tbl.checkRowDates(
                row.resultedValues!!.single(),
                date,
                null,
                time,
                null,
                dateTime,
                null,
                timestamp,
                null,
                duration,
                null
            )

            row[tbl.char] shouldBeEqualTo '('
        }
    }

    // these DB take the datetime nanosecond value and round up to default precision
    // which causes flaky comparison failures if not cast to TIMESTAMP first
    private val requiresExplicitDTCast = listOf(TestDB.H2_PSQL)

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select 01`(testDB: TestDB) {
        val tbl = Misc
        withTables(testDB, tbl) {
            val date = today
            val time = LocalTime.now()
            val dateTime = LocalDateTime.now()
            val timestamp = Instant.now()
            val duration = Duration.ofMinutes(1)
            val sTest = "test"
            val dec = BigDecimal("239.42")
            tbl.insert {
                it[by] = 13
                it[sm] = -10
                it[n] = 42
                it[d] = date
                it[t] = time
                it[dt] = dateTime
                it[ts] = timestamp
                it[dr] = duration
                it[e] = ONE
                it[es] = ONE
                it[c] = sTest
                it[s] = sTest
                it[dc] = dec
            }

            tbl.checkRowFull(
                tbl.selectAll().where { tbl.n.eq(42) }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                t = time,
                tn = null,
                dn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.nn.isNull() }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.nn.eq(null as Int?) }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )

            tbl.checkRowFull(
                tbl.selectAll().where { Misc.d.eq(date) }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
            tbl.checkRowFull(
                tbl.selectAll().where { Misc.dn.isNull() }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )

            val dtValue = when (testDB) {
                in requiresExplicitDTCast -> Cast(dateTimeParam(dateTime), JavaLocalDateTimeColumnType())
                else -> dateTimeParam(dateTime)
            }
            tbl.checkRowFull(
                tbl.selectAll().where { Misc.dt.eq(dtValue) }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
            tbl.checkRowFull(
                tbl.selectAll().where { Misc.dtn.isNull() }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )

            tbl.checkRowFull(
                tbl.selectAll().where { tbl.e.eq(ONE) }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.en.isNull() }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.en.eq(null as E?) }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )

            tbl.checkRowFull(
                tbl.selectAll().where { tbl.s.eq(sTest) }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.sn.isNull() }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.sn.eq(null as String?) }.single(),
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = ONE,
                en = null,
                es = ONE,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select 02`(testDB: TestDB) {
        val tbl = Misc
        withTables(testDB, tbl) {
            val date = today
            val time = LocalTime.now()
            val dateTime = LocalDateTime.now()
            val timestamp = Instant.now()
            val duration = Duration.ofMinutes(1)
            val sTest = "test"
            val eOne = ONE
            val dec = BigDecimal("239.42")
            tbl.insert {
                it[by] = 13
                it[byn] = 13
                it[sm] = -10
                it[smn] = -10
                it[n] = 42
                it[nn] = 42
                it[d] = date
                it[dn] = date
                it[t] = time
                it[tn] = time
                it[dt] = dateTime
                it[dtn] = dateTime
                it[ts] = timestamp
                it[tsn] = timestamp
                it[dr] = duration
                it[drn] = duration
                it[e] = eOne
                it[en] = eOne
                it[es] = eOne
                it[esn] = eOne
                it[c] = sTest
                it[cn] = sTest
                it[s] = sTest
                it[sn] = sTest
                it[dc] = dec
                it[dcn] = dec
                it[fcn] = 239.42f
                it[dblcn] = 567.89
            }

            tbl.checkRowFull(
                tbl.selectAll().where { tbl.nn.eq(42) }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.nn.neq(null) }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )

            tbl.checkRowFull(
                tbl.selectAll().where { Misc.dn.eq(date) }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )
            tbl.checkRowFull(
                tbl.selectAll().where { Misc.dn.isNotNull() }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )

            val dtValue = when (testDB) {
                in requiresExplicitDTCast -> Cast(dateTimeParam(dateTime), JavaLocalDateTimeColumnType())
                else -> dateTimeParam(dateTime)
            }
            tbl.checkRowFull(
                tbl.selectAll().where { Misc.dt.eq(dtValue) }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )
            tbl.checkRowFull(
                tbl.selectAll().where { Misc.dtn.isNotNull() }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )

            tbl.checkRowFull(
                tbl.selectAll().where { tbl.en.eq(eOne) }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.en.isNotNull() }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )

            tbl.checkRowFull(
                tbl.selectAll().where { tbl.sn.eq(sTest) }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )
            tbl.checkRowFull(
                tbl.selectAll().where { tbl.sn.isNotNull() }.single(),
                by = 13,
                byn = 13,
                sm = -10,
                smn = -10,
                n = 42,
                nn = 42,
                d = date,
                dn = date,
                t = time,
                tn = time,
                dt = dateTime,
                dtn = dateTime,
                ts = timestamp,
                tsn = timestamp,
                dr = duration,
                drn = duration,
                e = eOne,
                en = eOne,
                es = eOne,
                esn = eOne,
                c = sTest,
                cn = sTest,
                s = sTest,
                sn = sTest,
                dc = dec,
                dcn = dec,
                fcn = 239.42f,
                dblcn = 567.89
            )
        }
    }

    /**
     * Update nullable columns to null
     *
     * ```sql
     * -- Postgres
     * INSERT INTO misc ("by", byn, sm, smn, n, nn, d, dn, t, tn, dt, dtn, ts, tsn, dr, drn, e, en, es, esn, "c", s, sn, dc, dcn, fcn)
     * VALUES (13, 13, -10, -10, 42, 42, '2025-02-04', '2025-02-04', '10:56:20.258842', '10:56:20.258842', '2025-02-04T10:56:20.258846', '2025-02-04T10:56:20.258846', '2025-02-04T10:56:20.258852', '2025-02-04T10:56:20.258852', '60000000000', '60000000000', 0, 0, 'ONE', 'ONE', 'test', 'test', 'test', 239.42, 239.42, 239.42)
     * ```
     *
     * ```sql
     * -- Postgres
     * UPDATE misc
     *    SET byn=NULL, smn=NULL, nn=NULL, dn=NULL, tn=NULL, dtn=NULL, tsn=NULL, drn=NULL, en=NULL, esn=NULL, cn=NULL, sn=NULL, dcn=NULL, fcn=NULL WHERE misc.n = 42
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update 02`(testDB: TestDB) {
        val tbl = Misc
        withTables(testDB, tbl) {
            val date = today
            val time = LocalTime.now()
            val dateTime = LocalDateTime.now()
            val eOne = ONE
            val sTest = "test"
            val dec = BigDecimal("239.42")
            val timestamp = Instant.now()
            val duration = Duration.ofMinutes(1)

            tbl.insert {
                it[by] = 13
                it[byn] = 13
                it[sm] = -10
                it[smn] = -10
                it[n] = 42
                it[nn] = 42
                it[d] = date
                it[dn] = date
                it[t] = time
                it[tn] = time
                it[dt] = dateTime
                it[dtn] = dateTime
                it[ts] = timestamp
                it[tsn] = timestamp
                it[dr] = duration
                it[drn] = duration
                it[e] = eOne
                it[en] = eOne
                it[es] = eOne
                it[esn] = eOne
                it[c] = sTest
                it[s] = sTest
                it[sn] = sTest
                it[dc] = dec
                it[dcn] = dec
                it[fcn] = 239.42f
            }

            tbl.update({ tbl.n.eq(42) }) {
                it[byn] = null
                it[smn] = null
                it[nn] = null
                it[dn] = null
                it[tn] = null
                it[dtn] = null
                it[tsn] = null
                it[drn] = null
                it[en] = null
                it[esn] = null
                it[cn] = null
                it[sn] = null
                it[dcn] = null
                it[fcn] = null
            }

            val row = tbl.selectAll().single()
            tbl.checkRowFull(
                row,
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 42,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = eOne,
                en = null,
                es = eOne,
                esn = null,
                c = sTest,
                cn = null,
                s = sTest,
                sn = null,
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
        }
    }

    /**
     * Update varchar column
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update 03`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_MYSQL_MARIADB }
        val tbl = Misc
        val date = today
        val time = LocalTime.now()
        val dateTime = LocalDateTime.now()
        val timestamp = Instant.now()
        val duration = Duration.ofMinutes(1)
        val eOne = MiscTable.E.ONE
        val dec = BigDecimal("239.42")
        withTables(testDB, tbl) {
            tbl.insert {
                it[by] = 13
                it[sm] = -10
                it[n] = 101
                it[c] = "1234"
                it[cn] = "1234"
                it[s] = "123456789"
                it[sn] = "123456789"
                it[d] = date
                it[t] = time
                it[dt] = dateTime
                it[ts] = timestamp
                it[dr] = duration
                it[e] = eOne
                it[es] = eOne
                it[dc] = dec
            }

            /**
             * ```sql
             * UPDATE misc
             *    SET s=SUBSTRING(misc.s, 2, 255),
             *        sn=SUBSTRING(misc.s, 3, 255)
             *  WHERE misc.n = 101
             * ```
             */
            tbl.update({ tbl.n.eq(101) }) {
                it.update(s, tbl.s.substring(2, 255))
                it.update(sn) { tbl.s.substring(3, 255) }
            }

            val row = tbl.selectAll().where { tbl.n eq 101 }.single()

            tbl.checkRowFull(
                row,
                by = 13,
                byn = null,
                sm = -10,
                smn = null,
                n = 101,
                nn = null,
                d = date,
                dn = null,
                t = time,
                tn = null,
                dt = dateTime,
                dtn = null,
                ts = timestamp,
                tsn = null,
                dr = duration,
                drn = null,
                e = eOne,
                en = null,
                es = eOne,
                esn = null,
                c = "1234",
                cn = "1234",
                s = "23456789",
                sn = "3456789",
                dc = dec,
                dcn = null,
                fcn = null,
                dblcn = null
            )
        }
    }

    /**
     * ```sql
     * -- MySQL
     * CREATE TABLE `zerodatetimetable` (
     *     `id` INT NOT NULL AUTO_INCREMENT,
     *     `dt1` datetime NOT NULL,
     *     `dt2` datetime NULL,
     *     `ts1` timestamp NOT NULL,
     *     `ts2` timestamp NULL,
     *     PRIMARY KEY (`id`)
     * ) ENGINE=InnoDB
     * ```
     */
    private object ZeroDateTimeTable: Table("zerodatetimetable") {
        val id = integer("id")

        val dt1 = datetime("dt1").nullable() // We need nullable() to convert '0000-00-00 00:00:00' to null
        val dt2 = datetime("dt2").nullable()
        val ts1 = datetime("ts1").nullable() // We need nullable() to convert '0000-00-00 00:00:00' to null
        val ts2 = datetime("ts2").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    private val zeroDateTimeTableDdl = """
        CREATE TABLE `zerodatetimetable` (
        `id` INT NOT NULL AUTO_INCREMENT,
        `dt1` datetime NOT NULL,
        `dt2` datetime NULL,
        `ts1` timestamp NOT NULL,
        `ts2` timestamp NULL,
        PRIMARY KEY (`id`)
    ) ENGINE=InnoDB
    """.trimIndent()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testZeroDateTimeIsNull(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_MYSQL }
        withDb(testDB) {
            exec(zeroDateTimeTableDdl)
            try {
                // Need ignore to bypass strict mode
                exec("INSERT IGNORE INTO `zerodatetimetable` (dt1,dt2,ts1,ts2) VALUES ('0000-00-00 00:00:00', '0000-00-00 00:00:00', '0000-00-00 00:00:00', '0000-00-00 00:00:00');")
                val row = ZeroDateTimeTable.selectAll().first()

                listOf(
                    ZeroDateTimeTable.dt1,
                    ZeroDateTimeTable.dt2,
                    ZeroDateTimeTable.ts1,
                    ZeroDateTimeTable.ts2
                ).forEach { c ->
                    val actual = row[c]
                    assertNull(actual, "$c expected null but was $actual")
                }
                commit() // Need commit to persist data before drop tables
            } finally {
                SchemaUtils.drop(ZeroDateTimeTable)
                commit()
            }
        }
    }
}


@Suppress("LongParameterList")
fun Misc.checkRowFull(
    row: ResultRow,
    by: Byte,
    byn: Byte?,
    sm: Short,
    smn: Short?,
    n: Int,
    nn: Int?,
    d: LocalDate,
    dn: LocalDate?,
    t: LocalTime,
    tn: LocalTime?,
    dt: LocalDateTime,
    dtn: LocalDateTime?,
    ts: Instant,
    tsn: Instant?,
    dr: Duration,
    drn: Duration?,
    e: MiscTable.E,
    en: MiscTable.E?,
    es: MiscTable.E,
    esn: MiscTable.E?,
    c: String,
    cn: String?,
    s: String,
    sn: String?,
    dc: BigDecimal,
    dcn: BigDecimal?,
    fcn: Float?,
    dblcn: Double?,
) {
    checkRow(row, by, byn, sm, smn, n, nn, e, en, es, esn, c, cn, s, sn, dc, dcn, fcn, dblcn)
    checkRowDates(row, d, dn, t, tn, dt, dtn, ts, tsn, dr, drn)
}

@Suppress("LongParameterList")
fun Misc.checkRowDates(
    row: ResultRow,
    d: LocalDate,
    dn: LocalDate?,
    t: LocalTime,
    tn: LocalTime?,
    dt: LocalDateTime,
    dtn: LocalDateTime?,
    ts: Instant,
    tsn: Instant? = null,
    dr: Duration,
    drn: Duration? = null,
) {
    row[Misc.d] shouldTemporalEqualTo d
    row[Misc.dn] shouldTemporalEqualTo dn
    row[Misc.t] shouldTemporalEqualTo t
    row[Misc.tn] shouldTemporalEqualTo tn
    row[Misc.dt] shouldTemporalEqualTo dt
    row[Misc.dtn] shouldTemporalEqualTo dtn
    row[Misc.ts] shouldTemporalEqualTo ts
    row[Misc.tsn] shouldTemporalEqualTo tsn
    row[Misc.dr] shouldBeEqualTo dr
    row[Misc.drn] shouldBeEqualTo drn
}
