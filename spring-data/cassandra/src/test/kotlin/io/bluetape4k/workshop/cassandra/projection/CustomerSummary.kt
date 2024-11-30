package io.bluetape4k.workshop.cassandra.projection

import org.springframework.beans.factory.annotation.Value
import java.io.Serializable

interface CustomerSummary: Serializable {

    @get:Value("#{target.firstname + ' ' + target.lastname}")
    val firstname: String

}
