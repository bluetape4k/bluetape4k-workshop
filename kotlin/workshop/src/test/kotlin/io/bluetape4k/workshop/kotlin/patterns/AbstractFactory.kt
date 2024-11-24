package io.bluetape4k.workshop.kotlin.patterns

import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test

class AbstractFactory {

    @Test
    fun `Abstract Factory Pattern`() {
        val environment = Parser.server("port=8080", "host=localhost", "environment=production")

        environment.properties.forEach {
            println("${it.name} = ${it.value}")
        }

        environment.properties shouldHaveSize 3
    }

    private class Parser {
        companion object {
            fun server(vararg properties: String): ServerConfiguration {
                val parsedProps = properties.map { property(it) }
                return ServerConfigurationImpl(parsedProps)
            }

            fun property(prop: String): Property {
                val (name, value) = prop.split("=")
                return when (name) {
                    "port"        -> IntProperty(name, value.trim().toInt())
                    "host"        -> StringProperty(name, value.trim())
                    "environment" -> StringProperty(name, value.trim())
                    else          -> throw IllegalArgumentException("Unknown property: $name")
                }
            }
        }
    }

    private interface ServerConfiguration {
        val properties: List<Property>
    }

    private data class ServerConfigurationImpl(
        override val properties: List<Property>,
    ): ServerConfiguration

    private interface Property {
        val name: String
        val value: Any
    }

    private data class StringProperty(
        override val name: String,
        override val value: String,
    ): Property

    private data class IntProperty(
        override val name: String,
        override val value: Int,
    ): Property
}
