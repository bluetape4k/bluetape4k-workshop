package io.bluetape4k.workshop.coroutines.guide

sealed class Event {

    data object Created: Event() {
        override fun toString(): String = "Created"
    }

    data object Deleted: Event() {
        override fun toString(): String = "Deleted"
    }
}
