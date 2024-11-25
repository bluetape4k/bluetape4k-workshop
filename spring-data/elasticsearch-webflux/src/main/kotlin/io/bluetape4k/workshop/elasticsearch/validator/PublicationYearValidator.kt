package io.bluetape4k.workshop.elasticsearch.validator

import io.bluetape4k.workshop.elasticsearch.domain.metadata.PublicationYear
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import java.time.Year

/**
 * PublicationYear 를 검증하는 Validator
 */
class PublicationYearValidator: ConstraintValidator<PublicationYear, Int> {
    override fun isValid(value: Int?, context: ConstraintValidatorContext): Boolean {
        return value?.let { !Year.of(it).isAfter(Year.now()) } ?: false
    }
}
