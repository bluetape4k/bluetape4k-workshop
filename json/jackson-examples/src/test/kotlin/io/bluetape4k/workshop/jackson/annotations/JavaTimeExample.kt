package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonFormat
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * `@JsonFormat` 를 이용한 Java Time 날짜/시간 형식 변환 예제
 */
class JavaTimeExample: AbstractJacksonTest() {

    companion object: KLogging()

    private class JavaTime {

        var isoLocalDate: LocalDate? = null
        var isoLocalDateTime: LocalDateTime? = null
        var isoLocalTime: LocalTime? = null
        var isoYear: Year? = null
        var isoYearMonth: YearMonth? = null
        var isoMonthDay: MonthDay? = null
        var isoZonedDateTime: ZonedDateTime? = null
        var isoOffsetDateTime: OffsetDateTime? = null
        var isoOffsetTime: OffsetTime? = null
        var isoInstant: Instant? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy")
        var customLocalDate: LocalDate? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy|HH:mm")
        var customLocalDateTime: LocalDateTime? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH-mm")
        var customLocalTime: LocalTime? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "'Y'yyyy")
        var customYear: Year? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy:MM")
        var customYearMonth: YearMonth? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "MM_dd")
        var customMonthDay: MonthDay? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy|HH:mm|XXX")
        var customZonedDateTime: ZonedDateTime? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy|HH:mm|XXX")
        var customOffsetDateTime: OffsetDateTime? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm|XXX")
        var customOffsetTime: OffsetTime? = null

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy|HH:mm|XXX", timezone = "UTC")
        var customInstant: Instant? = null
    }

    /**
     * Java Time 모듈을 사용한 ObjectMapper
     */
    private val mapper = defaultMapper.rebuild().apply {
        configure(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
    }.build()

    // setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)  // null 값인 속성은 제외
    // disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)  // 날짜/시간을 변환할 때, context time zone 을 사용하지 않음

    private val LOCAL_DATE_TIME: LocalDateTime = LocalDateTime.of(2018, 1, 1, 14, 30, 22, 125000000)
    private val LOCAL_DATE: LocalDate = LOCAL_DATE_TIME.toLocalDate()
    private val LOCAL_TIME: LocalTime = LOCAL_DATE_TIME.toLocalTime()
    private val YEAR: Year = Year.of(2018)
    private val YEAR_MONTH: YearMonth = YearMonth.of(2018, 1)
    private val MONTH_DAY: MonthDay = MonthDay.of(1, 1)
    private val ZONED_DATE_TIME: ZonedDateTime =
        ZonedDateTime.of(2018, 1, 1, 14, 30, 22, 125000000, ZoneId.of("Europe/Madrid"))
    private val OFFSET_DATE_TIME: OffsetDateTime =
        OffsetDateTime.of(2018, 1, 1, 14, 30, 22, 125000000, ZoneOffset.ofHoursMinutes(2, 30))
    private val OFFSET_TIME: OffsetTime = OFFSET_DATE_TIME.toOffsetTime()
    private val INSTANT: Instant = ZONED_DATE_TIME.toInstant()

    @Test
    fun `LocalDateTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoLocalDateTime = LOCAL_DATE_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoLocalDateTime") shouldBeEqualTo "2018-01-01T14:30:22.125"
    }

    @Test
    fun `LocalDateTime conversion json to object`() {
        val json = """{"isoLocalDateTime":"2018-01-01T14:30:22.125"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoLocalDateTime shouldBeEqualTo LOCAL_DATE_TIME
    }

    @Test
    fun `LocalDate conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoLocalDate = LOCAL_DATE
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoLocalDate") shouldBeEqualTo "2018-01-01"
    }

    @Test
    fun `LocalDate conversion json to object`() {
        val json = """{"isoLocalDate":"2018-01-01"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoLocalDate shouldBeEqualTo LOCAL_DATE
    }

    @Test
    fun `LocalTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoLocalTime = LOCAL_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoLocalTime") shouldBeEqualTo "14:30:22.125"
    }

    @Test
    fun `LocalTime conversion json to object`() {
        val json = """{"isoLocalTime":"14:30:22.125"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoLocalTime shouldBeEqualTo LOCAL_TIME
    }

    @Test
    fun `Year conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoYear = YEAR
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoYear") shouldBeEqualTo "2018"
    }

    @Test
    fun `Year conversion json to object`() {
        val json = """{"isoYear":"2018"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoYear shouldBeEqualTo YEAR
    }

    @Test
    fun `YearMonth conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoYearMonth = YEAR_MONTH
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoYearMonth") shouldBeEqualTo "2018-01"
    }

    @Test
    fun `YearMonth conversion json to object`() {
        val json = """{"isoYearMonth":"2018-01"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoYearMonth shouldBeEqualTo YEAR_MONTH
    }

    @Test
    fun `MonthDay conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoMonthDay = MONTH_DAY
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoMonthDay") shouldBeEqualTo "--01-01"
    }

    @Test
    fun `MonthDay conversion json to object`() {
        val json = """{"isoMonthDay":"--01-01"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoMonthDay shouldBeEqualTo MONTH_DAY
    }

    @Test
    fun `ZonedDateTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoZonedDateTime = ZONED_DATE_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoZonedDateTime") shouldBeEqualTo "2018-01-01T14:30:22.125+01:00"
    }

    @Test
    fun `ZonedDateTime conversion json to object`() {
        val json = """{"isoZonedDateTime":"2018-01-01T14:30:22.125+01:00"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoZonedDateTime shouldBeEqualTo ZONED_DATE_TIME.withFixedOffsetZone()
    }

    @Test
    fun `OffsetDateTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoOffsetDateTime = OFFSET_DATE_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoOffsetDateTime") shouldBeEqualTo "2018-01-01T14:30:22.125+02:30"
    }

    @Test
    fun `OffsetDateTime conversion json to object`() {
        val json = """{"isoOffsetDateTime":"2018-01-01T14:30:22.125+02:30"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoOffsetDateTime shouldBeEqualTo OFFSET_DATE_TIME
    }

    @Test
    fun `OffsetTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoOffsetTime = OFFSET_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoOffsetTime") shouldBeEqualTo "14:30:22.125+02:30"
    }

    @Test
    fun `OffsetTime conversion json to object`() {
        val json = """{"isoOffsetTime":"14:30:22.125+02:30"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoOffsetTime shouldBeEqualTo OFFSET_TIME
    }

    @Test
    fun `Instant conversion object to json`() {
        val javaTime = JavaTime().apply {
            isoInstant = INSTANT
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.isoInstant") shouldBeEqualTo "2018-01-01T13:30:22.125Z"
    }

    @Test
    fun `Instant conversion json to object`() {
        val json = """{"isoInstant":"2018-01-01T13:30:22.125Z"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.isoInstant shouldBeEqualTo INSTANT
    }

    @Test
    fun `Custom LocalDateTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            customLocalDateTime = LOCAL_DATE_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customLocalDateTime") shouldBeEqualTo "01/01/2018|14:30"
    }

    @Test
    fun `Custom LocalDateTime conversion json to object`() {
        val json = """{"customLocalDateTime":"01/01/2018|14:30"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customLocalDateTime shouldBeEqualTo LOCAL_DATE_TIME.truncatedTo(ChronoUnit.MINUTES)
    }

    @Test
    fun `Custom LocalDate conversion object to json`() {
        val javaTime = JavaTime().apply {
            customLocalDate = LOCAL_DATE
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customLocalDate") shouldBeEqualTo "01/01/2018"
    }

    @Test
    fun `Custom LocalDate conversion json to object`() {
        val json = """{"customLocalDate":"01/01/2018"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customLocalDate shouldBeEqualTo LOCAL_DATE
    }

    @Test
    fun `Custom LocalTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            customLocalTime = LOCAL_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customLocalTime") shouldBeEqualTo "14-30"
    }

    @Test
    fun `Custom LocalTime conversion json to object`() {
        val json = """{"customLocalTime":"14-30"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customLocalTime shouldBeEqualTo LOCAL_TIME.truncatedTo(ChronoUnit.MINUTES)
    }

    @Test
    fun `Custom Year conversion object to json`() {
        val javaTime = JavaTime().apply {
            customYear = YEAR
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customYear") shouldBeEqualTo "Y2018"
    }

    @Test
    fun `Custom Year conversion json to object`() {
        val json = """{"customYear":"Y2018"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customYear shouldBeEqualTo YEAR
    }

    @Test
    fun `Custom YearMonth conversion object to json`() {
        val javaTime = JavaTime().apply {
            customYearMonth = YEAR_MONTH
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customYearMonth") shouldBeEqualTo "2018:01"
    }

    @Test
    fun `Custom YearMonth conversion json to object`() {
        val json = """{"customYearMonth":"2018:01"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customYearMonth shouldBeEqualTo YEAR_MONTH
    }

    @Test
    fun `Custom MonthDay conversion object to json`() {
        val javaTime = JavaTime().apply {
            customMonthDay = MONTH_DAY
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customMonthDay") shouldBeEqualTo "01_01"
    }

    @Test
    fun `Custom MonthDay conversion json to object`() {
        val json = """{"customMonthDay":"01_01"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customMonthDay shouldBeEqualTo MONTH_DAY
    }

    @Test
    fun `Custom ZonedDateTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            customZonedDateTime = ZONED_DATE_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customZonedDateTime") shouldBeEqualTo "01/01/2018|14:30|+01:00"
    }

    @Test
    fun `Custom ZonedDateTime conversion json to object`() {
        val json = """{"customZonedDateTime":"01/01/2018|14:30|+01:00"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customZonedDateTime shouldBeEqualTo ZONED_DATE_TIME.truncatedTo(ChronoUnit.MINUTES)
            .withFixedOffsetZone()
    }

    @Test
    fun `Custom OffsetDateTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            customOffsetDateTime = OFFSET_DATE_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customOffsetDateTime") shouldBeEqualTo "01/01/2018|14:30|+02:30"
    }

    @Test
    fun `Custom OffsetDateTime conversion json to object`() {
        val json = """{"customOffsetDateTime":"01/01/2018|14:30|+02:30"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customOffsetDateTime shouldBeEqualTo OFFSET_DATE_TIME.truncatedTo(ChronoUnit.MINUTES)
    }

    @Test
    fun `Custom OffsetTime conversion object to json`() {
        val javaTime = JavaTime().apply {
            customOffsetTime = OFFSET_TIME
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customOffsetTime") shouldBeEqualTo "14:30|+02:30"
    }

    @Test
    fun `Custom OffsetTime conversion json to object`() {
        val json = """{"customOffsetTime":"14:30|+02:30"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customOffsetTime shouldBeEqualTo OFFSET_TIME.truncatedTo(ChronoUnit.MINUTES)
    }

    @Test
    fun `Custom Instant conversion object to json`() {
        val javaTime = JavaTime().apply {
            customInstant = INSTANT
        }
        val json = mapper.writeValueAsString(javaTime)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.customInstant") shouldBeEqualTo "01/01/2018|13:30|Z"
    }

    @Test
    fun `Custom Instant conversion json to object`() {
        val json = """{"customInstant":"01/01/2018|13:30|Z"}"""
        val javaTime = mapper.readValue<JavaTime>(json)
        javaTime.customInstant shouldBeEqualTo INSTANT.truncatedTo(ChronoUnit.MINUTES)
    }
}
