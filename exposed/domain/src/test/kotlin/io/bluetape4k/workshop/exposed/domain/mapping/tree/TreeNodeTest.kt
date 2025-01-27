package io.bluetape4k.workshop.exposed.domain.mapping.tree

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class TreeNodeTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * 트리 노드 테이블
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS TREE_NODES (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      TITLE VARCHAR(255) NOT NULL,
     *      DESCRIPTION TEXT NULL,
     *      PARENT_ID BIGINT NULL
     * )
     * ```
     */
    object TreeNodeTable: LongIdTable("tree_nodes") {
        val title = varchar("title", 255)
        val description = text("description").nullable()

        val parentId = reference("parent_id", TreeNodeTable).nullable()
    }

    class TreeNode(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<TreeNode>(TreeNodeTable)

        var title by TreeNodeTable.title
        var description by TreeNodeTable.description
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

        override fun equals(other: Any?): Boolean = other is TreeNode && id._value == other.id._value
        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String = "TreeNode(id=${id._value}, title=$title), parent_id=${parent?.id?._value})"
    }

    private fun buildTreeNodes() {
        val root = TreeNode.new { title = "root" }
        val child1 = TreeNode.new { title = "child1"; parent = root }
        val child2 = TreeNode.new { title = "child2"; parent = root }

        val grandChild1 = TreeNode.new { title = "grandChild1"; parent = child1 }
        val grandChild2 = TreeNode.new { title = "grandChild2"; parent = child1 }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `build tree nodes`(testDB: TestDB) {
        withTables(testDB, TreeNodeTable) {
            buildTreeNodes()

            val root = TreeNode.find { TreeNodeTable.title eq "root" }.single()
            val child1 = TreeNode.find { TreeNodeTable.title eq "child1" }.single()
            val child2 = TreeNode.find { TreeNodeTable.title eq "child2" }.single()
            val grandChild1 = TreeNode.find { TreeNodeTable.title eq "grandChild1" }.single()
            val grandChild2 = TreeNode.find { TreeNodeTable.title eq "grandChild2" }.single()

            entityCache.clear()

            val loadedChild1 = TreeNode.findById(child1.id)!!
            loadedChild1 shouldBeEqualTo child1
            loadedChild1.parent shouldBeEqualTo root

            loadedChild1.children.count() shouldBeEqualTo 2L
            loadedChild1.children.toSet() shouldContainSame setOf(grandChild1, grandChild2)

            // 모든 Root 노드 조회
            val roots = TreeNode.find { TreeNodeTable.parentId.isNull() }.toList()
            roots shouldBeEqualTo listOf(root)

            // child1 및 자손들을 모두 삭제
            child1.deleteDescendants()

            root.children.count() shouldBeEqualTo 1L
            TreeNode.findById(grandChild1.id).shouldBeNull()
            TreeNode.findById(grandChild2.id).shouldBeNull()

            // child1 은 삭제되었지만, child2 는 삭제되지 않았다.
            TreeNode.findById(child1.id).shouldBeNull()
            TreeNode.findById(child2.id).shouldNotBeNull()

//            // child1 삭제
//            child1.delete()
//            root.children.count() shouldBeEqualTo 1L
//
//            // NOTE: child1 삭제 시 자손은 삭제되지 않는다. 단 부모가 NULL 로 변경된다.
//            // 모든 자손을 삭제하기 위해서는 Depth-First Search 방식으로 삭제해야 한다.
//            val loadedGrandChild1 = TreeNode.findById(grandChild1.id)
//            loadedGrandChild1.shouldNotBeNull()
//            log.debug { "loadedGrandChild1: $loadedGrandChild1" }
        }
    }
}
