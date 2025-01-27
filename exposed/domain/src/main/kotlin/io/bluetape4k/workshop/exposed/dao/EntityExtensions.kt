package io.bluetape4k.workshop.exposed.dao

import org.jetbrains.exposed.dao.Entity

val <ID: Any> Entity<ID>.idValue: Any? get() = id._value
