package io.bluetape4k.workshop.redis.repositories

import io.bluetape4k.AbstractValueObject
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.support.hashOf
import io.bluetape4k.support.requireNotBlank
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Reference
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.index.Indexed

/**
 * Redis `HASH` 안에 저장되는 [Person] 객체입니다.
 *
 * 예제 (key = persons:9b0ed8ee-14be-46ec-b5fa-79570aadb91d):
 *
 * ```
 * _class := example.springdata.redis.domain.Person
 * id := 9b0ed8ee-14be-46ec-b5fa-79570aadb91d
 * firstname := eddard
 * lastname := stark
 * gender := MALE
 * address.city := winterfell
 * address.country := the north
 * children.[0] := persons:41436096-aabe-42fa-bd5a-9a517fbf0260
 * children.[1] := persons:1973d8e7-fbd4-4f93-abab-a2e3a00b3f53
 * children.[2] := persons:440b24c6-ede2-495a-b765-2d8b8d6e3995
 * children.[3] := persons:85f0c1d1-cef6-40a4-b969-758ebb68dd7b
 * children.[4] := persons:73cb36e8-add9-4ec0-b5dd-d820e04f44f0
 * children.[5] := persons:9c2461aa-2ef2-469f-83a2-bd216df8357f
 * ```
 */
@RedisHash("persons")
class Person: AbstractValueObject() {

    companion object {
        @JvmStatic
        operator fun invoke(firstname: String, lastname: String, gender: Gender = Gender.UNKNOWN): Person {
            return Person().apply {
                this.firstname = firstname
                this.lastname = lastname
                this.gender = gender
            }
        }
    }


    /**
     * [id] 와 [RedisHash#toString()] 으로 Redis `HASH` 의 `key` 를 구성합니다.
     *
     * ```
     * RedisHash.value() + ":" + Person.id
     * // 예: persons:9b0ed8ee-14be-46ec-b5fa-79570aadb91d
     * ```
     *
     * **참고: 비어 있는 [id] 필드는 저장 작업 중 자동으로 할당됩니다.**
     */
    @Id
    var id: String? = null

    val identifier: String get() = id.requireNotBlank("id")

    /**
     * [Indexed] 를 사용하면 이 속성을 인덱싱 대상으로 표시합니다.
     * Redis `SET` 으로 값이 일치하는 객체의 `ids` 를 추적합니다.
     *
     * ```
     * RedisHash.value() + ":" + Field.getName() + ":" + Field.get(Object)
     * // 예: persons:firstname:eddard
     * ```
     */
    @Indexed
    var firstname: String = ""

    @Indexed
    var lastname: String = ""

    var gender: Gender = Gender.UNKNOWN

    /**
     * [Address.city] 에 [Indexed] 를 사용하므로 `persons:address:city` 인덱스 구조가 유지됩니다.
     */
    var address: Address? = null

    /**
     * [Reference] 를 사용하면 기존 객체를 해당 `key` 로 연결할 수 있습니다.
     * 객체의 `HASH` 에 저장되는 값은 다음과 같습니다.
     *
     * ```
     * children.[0] := persons:41436096-aabe-42fa-bd5a-9a517fbf0260
     * children.[1] := persons:1973d8e7-fbd4-4f93-abab-a2e3a00b3f53
     * children.[2] := persons:440b24c6-ede2-495a-b765-2d8b8d6e3995
     * ```
     */
    @Reference
    var children: MutableList<Person> = mutableListOf()
        private set


    override fun equalProperties(other: Any): Boolean {
        return other is Person &&
                id == other.id &&
                firstname == other.firstname &&
                lastname == other.lastname
    }

    override fun equals(other: Any?): Boolean = other != null && super.equals(other)

    override fun hashCode(): Int = id?.hashCode() ?: hashOf(firstname, lastname)

    override fun buildStringHelper(): ToStringBuilder {
        return super.buildStringHelper()
            .add("id", id)
            .add("firstname", firstname)
            .add("lastname", lastname)
    }
}
