package io.bluetape4k.workshop.exposed.domain.mapping.entities

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.idValue
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.javatime.date

object PostTable: LongIdTable("posts") {
    val title = varchar("title", 255)
}

object PostDetailsTable: IdTable<Long>("post_details") {
    override val id: Column<EntityID<Long>> = reference("id", PostTable)   // one-to-one relationship
    val createdOn = date("created_on")
    val createdBy = varchar("created_by", 255)
}

object PostCommentTable: LongIdTable("post_comments") {
    val postId = reference("post_id", PostTable).index()
    val review = varchar("review", 255)
}

/**
 * Many-to-many relationship table
 */
object PostTagTable: LongIdTable("post_tags") {
    val postId = reference("post_id", PostTable, onDelete = ReferenceOption.CASCADE)
    val tagId = reference("tag_id", TagTable, onDelete = ReferenceOption.CASCADE)

    init {
        uniqueIndex(postId, tagId)
    }
}

object TagTable: LongIdTable("tags") {
    val name = varchar("name", 255)
}

class Post(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<Post>(PostTable)

    var title by PostTable.title

    val details: PostDetails by PostDetails backReferencedOn PostDetailsTable.id  // one-to-one relationship
    val comments: SizedIterable<PostComment> by PostComment referrersOn PostCommentTable.postId
    val tags: SizedIterable<Tag> by Tag via PostTagTable // Tag.via (PostTagTable.post, PostTagTable.tag)

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String =
        toStringBuilder()
            .add("title", title)
            .toString()
}

class PostDetails(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<PostDetails>(PostDetailsTable)

    val post: Post by Post referencedOn PostDetailsTable.id   // one-to-one relationship
    var createdOn by PostDetailsTable.createdOn
    var createdBy by PostDetailsTable.createdBy

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String =
        toStringBuilder()
            .add("post id", post.idValue)
            .add("createdOn", createdOn)
            .add("createdBy", createdBy)
            .toString()
}

class PostComment(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<PostComment>(PostCommentTable)

    // one-to-many relationship
    var post by Post referencedOn PostCommentTable.postId
    var review by PostCommentTable.review

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String =
        toStringBuilder()
            .add("post id", post.idValue)
            .add("review", review)
            .toString()
}

class Tag(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<Tag>(TagTable)

    var name by TagTable.name
    val posts: SizedIterable<Post> by Post via PostTagTable // Post.via(PostTagTable.tag, PostTagTable.post)

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String =
        toStringBuilder()
            .add("name", name)
            .toString()
}
