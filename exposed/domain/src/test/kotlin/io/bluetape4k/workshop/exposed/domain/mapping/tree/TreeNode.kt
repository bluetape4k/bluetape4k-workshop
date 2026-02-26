package io.bluetape4k.workshop.exposed.domain.mapping.tree

import io.bluetape4k.exposed.dao.entityToStringBuilder
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.idValue
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * 트리 노드 테이블
 *
 * ```sql
 * CREATE TABLE IF NOT EXISTS TREE_NODES (
 *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
 *      TITLE VARCHAR(255) NOT NULL,
 *      DESCRIPTION TEXT NULL,
 *      DEPTH INT DEFAULT 0,
 *      PARENT_ID BIGINT NULL
 * )
 * ```
 */
object TreeNodeTable: LongIdTable("tree_nodes") {
    val title = varchar("title", 255)
    val description = text("description").nullable()
    val depth = integer("depth").default(0)

    val parentId = reference("parent_id", TreeNodeTable).nullable()
}

class TreeNode(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TreeNode>(TreeNodeTable) {
        override fun new(init: TreeNode.() -> Unit): TreeNode {
            val node = super.new { }
            node.init()
            node.depth = (node.parent?.depth ?: 0) + 1
            return node
        }
    }

    var title by TreeNodeTable.title
    var description by TreeNodeTable.description
    var depth by TreeNodeTable.depth
    var parent by TreeNode optionalReferencedOn TreeNodeTable.parentId

    // 자식 노드 조회
    val children
        get() = TreeNode.find { TreeNodeTable.parentId eq id }

    /**
     * 자신뿐 아니라 모든 자손까지 삭제한다.
     */
    fun deleteDescendants() {
        children.forEach { it.deleteDescendants() }
        delete()
    }

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = entityToStringBuilder()
        .add("title", title)
        .add("description", description)
        .add("depth", depth)
        .add("parent id", parent?.idValue)
        .toString()
}

internal fun JdbcTransaction.buildTreeNodes() {
    val root = TreeNode.new { title = "root" }
    commit()

    val child1 = TreeNode.new { title = "child1"; parent = root }
    val child2 = TreeNode.new { title = "child2"; parent = root }
    commit()

    val grandChild1 = TreeNode.new { title = "grandChild1"; parent = child1; }
    val grandChild2 = TreeNode.new { title = "grandChild2"; parent = child1; }
    commit()
}
