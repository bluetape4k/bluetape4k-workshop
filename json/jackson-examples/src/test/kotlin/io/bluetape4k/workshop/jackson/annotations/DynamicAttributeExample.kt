package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue

/**
 * `@JsonAnyGetter` 와 `@JsonAnySetter` 를 이용하여 동적 속성을 처리하는 예제
 */
class DynamicAttributeExample: AbstractJacksonTest() {

    companion object: KLogging()

    private class File(
        val name: String,
    ) {
        companion object: KLogging()

        @JsonAnyGetter
        val attrs: MutableMap<String, String> = mutableMapOf()

        @JsonAnySetter
        fun addAttr(key: String, value: String) {
            log.debug { "Add attr. key=$key, value=$value" }
            attrs[key] = value
        }

        fun getAttr(key: String): String? {
            return attrs[key]
        }
    }

    @Test
    fun `Dynamic attributes conversion object to json`() {
        val props = hashMapOf(
            "size" to "120 KB",
            "width" to "280 px",
            "height" to "300 px",
        )
        val file = File("pic.jpg").apply { attrs.putAll(props) }
        val json = defaultMapper.writeValueAsString(file)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.name") shouldBeEqualTo "pic.jpg"
        doc.readAs<String>("$.size") shouldBeEqualTo "120 KB"
        doc.readAs<String>("$.width") shouldBeEqualTo "280 px"
        doc.readAs<String>("$.height") shouldBeEqualTo "300 px"
    }

    @Test
    fun `Dynamic attributes conversion json to object`() {
        val json = """
            {
              "name" : "pic.jpg",
              "width" : "280 px",
              "size" : "120 KB",
              "height" : "300 px"
            }
            """.trimIndent()

        val file = defaultMapper.readValue<File>(json)
        log.debug { "File name=${file.name}, attrs=${file.attrs}" }

        file.name shouldBeEqualTo "pic.jpg"
        file.getAttr("size") shouldBeEqualTo "120 KB"
        file.getAttr("width") shouldBeEqualTo "280 px"
        file.getAttr("height") shouldBeEqualTo "300 px"
    }
}
