package io.bluetape4k.workshop.exposed.domain.mapping.entities

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
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
    val post = reference("post_id", PostTable)
    val review = varchar("review", 255)
}

object PostTagTable: LongIdTable("post_tags") {
    val post = reference("post_id", PostTable, onDelete = ReferenceOption.CASCADE)
    val tag = reference("tag_id", TagTable, onDelete = ReferenceOption.CASCADE)
}

object TagTable: LongIdTable("tags") {
    val name = varchar("name", 255)
}

class Post(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<Post>(PostTable)

    var title by PostTable.title

    val details by PostDetails backReferencedOn PostDetailsTable.id
    val comments by PostComment referrersOn PostCommentTable.post
    val tags by Tag.via(PostTagTable.post, PostTagTable.tag)

    override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other is Post && other.id == this.id
    override fun toString(): String {
        return "Post(id=$id, title=$title)"
    }
}

class PostDetails(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<PostDetails>(PostDetailsTable)

    val post by Post referencedOn PostDetailsTable.id
    var createdOn by PostDetailsTable.createdOn
    var createdBy by PostDetailsTable.createdBy

    override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other is PostDetails && other.id == this.id
    override fun toString(): String {
        return "Post(id=$id, createdOn=$createdOn, createdBy=$createdBy)"
    }
}

class PostComment(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<PostComment>(PostCommentTable)

    // one-to-many relationship
    var post by Post referencedOn PostCommentTable.post
    var review by PostCommentTable.review

    override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other is PostComment && other.id == this.id
    override fun toString(): String {
        return "Post(id=$id, postId=${post.id}, review=$review)"
    }
}

class Tag(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<Tag>(TagTable)

    var name by TagTable.name
    val posts by Post.via(PostTagTable.tag, PostTagTable.post)

    override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other is Tag && other.id == this.id
    override fun toString(): String {
        return "Post(id=$id, name=$name)"
    }
}
