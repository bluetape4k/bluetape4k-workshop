package io.bluetape4k.workshop.elasticsearch.domain.metadata

import jakarta.validation.Constraint
import kotlin.reflect.KClass

@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Constraint(validatedBy = [io.bluetape4k.workshop.elasticsearch.validator.PublicationYearValidator::class])
annotation class PublicationYear(
    val message: String = "Publication year cannot be future year.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Any>> = [],
) 
