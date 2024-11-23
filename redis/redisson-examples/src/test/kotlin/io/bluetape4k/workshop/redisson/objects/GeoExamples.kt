package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.redisson.coroutines.coAwait
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.api.GeoEntry
import org.redisson.api.GeoPosition
import org.redisson.api.GeoUnit
import org.redisson.api.geo.GeoSearchArgs


/**
 * [RGeo] examples
 *
 * Java implementation of Redis based RGeo object is a holder for geospatial items.
 *
 * 참고: [Geospatial Holder](https://github.com/redisson/redisson/wiki/6.-distributed-objects/#63-geospatial-holder)
 */
class GeoExamples: AbstractRedissonTest() {

    companion object: KLogging()

    @Test
    fun `RGeo example`() = runSuspendIO {

        val geo = redisson.getGeo<String>(randomName())

        val palermo = GeoEntry(13.361389, 38.115556, "Palermo")
        val catania = GeoEntry(15.087269, 37.502669, "Catania")

        geo.addAsync(palermo, catania).coAwait() shouldBeEqualTo 2L

        val dist = geo.distAsync("Palermo", "Catania", GeoUnit.METERS).coAwait()
        val pos = geo.posAsync("Palermo", "Catania").coAwait()

        log.debug { "distance=$dist, pos=$pos" }

        // 중심점으로부터 반경 200 km 내의 도시 찾기
        val fromLocation = GeoSearchArgs.from(15.0, 37.0).radius(200.0, GeoUnit.KILOMETERS)
        val cities = geo.searchAsync(fromLocation).coAwait()
        cities shouldBeEqualTo listOf("Palermo", "Catania")

        // Palermo 시를 중심으로 반경 10 km 내의 도시 찾기
        val fromPalermo = GeoSearchArgs.from("Palermo").radius(10.0, GeoUnit.KILOMETERS)
        val allNearCities = geo.searchAsync(fromPalermo).coAwait()
        allNearCities shouldBeEqualTo listOf("Palermo")


        val geoSearch200 = GeoSearchArgs.from(15.0, 37.0).radius(200.0, GeoUnit.KILOMETERS)
        val citiesWithDistance = geo.searchWithDistanceAsync(geoSearch200).coAwait()
        citiesWithDistance.forEach { (city, distance) ->
            log.debug { "city=$city, distance from (15.0, 37.0)=$distance km" }
        }

        val fromPalermo200 = GeoSearchArgs.from("Palermo").radius(200.0, GeoUnit.KILOMETERS)
        val allNearCitiesDistance = geo.searchWithDistanceAsync(fromPalermo200).coAwait()
        allNearCitiesDistance.forEach { (city, distance) ->
            log.debug { "city=$city, distance from Palermo=$distance km" }
        }

        val citiesWithPosition = geo.searchWithPositionAsync(geoSearch200).coAwait()
        citiesWithPosition.forEach { (city, position) ->
            log.debug { "city=$city, position=$position" }
        }

        val allNearCitiesPosition: MutableMap<String, GeoPosition> =
            geo.searchWithPositionAsync(fromPalermo200).coAwait()
        allNearCitiesPosition.forEach { (city, position) ->
            log.debug { "city=$city, position=$position" }
        }

        geo.deleteAsync().coAwait().shouldBeTrue()
    }
}
