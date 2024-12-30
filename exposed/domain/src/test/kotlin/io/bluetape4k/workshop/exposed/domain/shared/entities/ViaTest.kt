package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.SnowflakeIdTable
import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.InnerTableLink
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.*
import kotlin.reflect.jvm.isAccessible

object ViaTestData {

    object NumbersTable: TimebasedUUIDTable() {
        val number = integer("number")
    }

    object StringsTable: SnowflakeIdTable("") {
        val text = varchar("text", 10)
    }

    interface IConnectionTable {
        val numId: Column<EntityID<UUID>>
        val stringId: Column<EntityID<Long>>
    }

    object ConnectionTable: TimebasedUUIDTable(), IConnectionTable {
        override val numId = reference("numId", NumbersTable)
        override val stringId = reference("stringId", StringsTable)

        init {
            uniqueIndex(numId, stringId)
        }
    }

    object ConnectionAutoTable: IntIdTable(), IConnectionTable {
        override val numId = reference("numId", NumbersTable, onDelete = ReferenceOption.CASCADE)
        override val stringId = reference("stringId", StringsTable, onDelete = ReferenceOption.CASCADE)

        init {
            uniqueIndex(numId, stringId)
        }
    }

    val allTables = arrayOf(NumbersTable, StringsTable, ConnectionTable, ConnectionAutoTable)
}

class VNumber(id: EntityID<UUID>): UUIDEntity(id) {
    companion object: UUIDEntityClass<VNumber>(ViaTestData.NumbersTable)

    var number: Int by ViaTestData.NumbersTable.number
    var connectedStrings: SizedIterable<VString> by VString via ViaTestData.ConnectionTable
    var connectedAutoStrings: SizedIterable<VString> by VString via ViaTestData.ConnectionAutoTable
}

class VString(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<VString>(ViaTestData.StringsTable)

    var text: String by ViaTestData.StringsTable.text
}

class ViaTest: AbstractExposedTest() {

    private fun VNumber.testWithBothTables(
        valuesToSet: List<VString>,
        body: (ViaTestData.IConnectionTable, List<ResultRow>) -> Unit,
    ) {
        listOf(ViaTestData.ConnectionTable, ViaTestData.ConnectionAutoTable).forEach { ct ->
            when (ct) {
                is ViaTestData.ConnectionTable -> connectedStrings = SizedCollection(valuesToSet)
                is ViaTestData.ConnectionAutoTable -> connectedAutoStrings = SizedCollection(valuesToSet)
            }

            val result = ct.selectAll().toList()
            body(ct, result)
        }
    }

    @Test
    fun `connection 01`() {
        withTables(*ViaTestData.allTables) {
            val n = VNumber.new { number = 42 }
            val s = VString.new { text = "foo" }

            n.testWithBothTables(listOf(s)) { ct, result ->
                val row = result.single()
                row[ct.numId] shouldBeEqualTo n.id
                row[ct.stringId] shouldBeEqualTo s.id
            }
        }
    }

    @Test
    fun `connection 02`() {
        withTables(*ViaTestData.allTables) {
            val n1 = VNumber.new { number = 1 }
            val n2 = VNumber.new { number = 2 }

            val s1 = VString.new { text = "foo" }
            val s2 = VString.new { text = "bar" }

            n1.testWithBothTables(listOf(s1, s2)) { ct, row ->
                row.count() shouldBeEqualTo 2

                row[0][ct.numId] shouldBeEqualTo n1.id
                row[1][ct.numId] shouldBeEqualTo n1.id
                row.map { it[ct.stringId] } shouldBeEqualTo listOf(s1.id, s2.id)
            }
        }
    }

    @Test
    fun `connection 03`() {
        withTables(*ViaTestData.allTables) {
            val n1 = VNumber.new { number = 1 }
            val n2 = VNumber.new { number = 2 }

            val s1 = VString.new { text = "aaa" }
            val s2 = VString.new { text = "bbb" }

            n1.testWithBothTables(listOf(s1, s2)) { _, _ -> }
            n2.testWithBothTables(listOf(s1, s2)) { _, row ->
                row.count() shouldBeEqualTo 4
                n1.connectedStrings.toList() shouldBeEqualTo listOf(s1, s2)
                n2.connectedStrings.toList() shouldBeEqualTo listOf(s1, s2)
            }
            n1.testWithBothTables(emptyList()) { table, row ->
                row.count() shouldBeEqualTo 2
                row[0][table.numId] shouldBeEqualTo n2.id
                row[1][table.numId] shouldBeEqualTo n2.id
                n1.connectedStrings.toList().shouldBeEmpty()
                n2.connectedStrings.toList() shouldBeEqualTo listOf(s1, s2)
            }
        }
    }

    @Test
    fun `connection 04`() {
        withTables(*ViaTestData.allTables) {
            val n1 = VNumber.new { number = 1 }
            val n2 = VNumber.new { number = 2 }

            val s1 = VString.new { text = "aaa" }
            val s2 = VString.new { text = "bbb" }

            n1.testWithBothTables(listOf(s1, s2)) { _, _ -> }
            n2.testWithBothTables(listOf(s1, s2)) { _, row ->
                row.count() shouldBeEqualTo 4
                n1.connectedStrings.toList() shouldBeEqualTo listOf(s1, s2)
                n2.connectedStrings.toList() shouldBeEqualTo listOf(s1, s2)
            }
            // SizedCollection에서 제거하면 cascade delete 되어 s2 가 삭제된다.
            n1.testWithBothTables(listOf(s1)) { _, row ->
                row.count() shouldBeEqualTo 3
                n1.connectedStrings.toList() shouldBeEqualTo listOf(s1)
                n2.connectedStrings.toList() shouldBeEqualTo listOf(s1, s2)
            }
        }
    }

    object NodesTable: IntIdTable() {
        val name = varchar("name", 50)
    }

    object NodeToNodes: Table() {
        val parent = reference("parent_node_id", NodesTable)
        val child = reference("child_node_id", NodesTable)
    }

    class Node(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Node>(NodesTable)

        var name by NodesTable.name
        var parents by Node.via(NodeToNodes.child, NodeToNodes.parent)
        var children by Node.via(NodeToNodes.parent, NodeToNodes.child)

        override fun equals(other: Any?): Boolean = (other as? Node)?.id == id
        override fun hashCode(): Int = Objects.hash(id)
        override fun toString(): String = "Node($id)"
    }

    @Test
    fun `hierarchy references`() {
        withTables(NodesTable, NodeToNodes) {
            val root = Node.new { name = "root" }
            val child1 = Node.new {
                name = "child1"
                parents = SizedCollection(root)
            }

            root.parents.count() shouldBeEqualTo 0L
            root.children.count() shouldBeEqualTo 1L

            val child2 = Node.new { name = "child2" }
            root.children = SizedCollection(child1, child2)

            child1.parents.singleOrNull() shouldBeEqualTo root
            child2.parents.singleOrNull() shouldBeEqualTo root
        }
    }

    @Test
    fun `refresh entity`() {
        withTables(*ViaTestData.allTables) {
            val s = VString.new { text = "foo" }.apply {
                refresh(true)
            }
            // SELECT strings.id, strings.text FROM strings WHERE strings.id = 1323282225663311872
            s.text shouldBeEqualTo "foo"
        }
    }

    @Test
    fun `warm up on hierarchy entities`() {
        withTables(NodesTable, NodeToNodes) {
            val child1 = Node.new { name = "child1" }
            val child2 = Node.new { name = "child2" }
            val root1 = Node.new {
                name = "root1"
                children = SizedCollection(child1)
            }
            val root2 = Node.new {
                name = "root2"
                children = SizedCollection(child1, child2)
            }

            entityCache.clear(flush = true)

            fun checkChildrenReferences(node: Node, values: List<Node>) {
                val sourceColumn = (Node::children
                    .apply { isAccessible = true }.getDelegate(node) as InnerTableLink<*, *, *, *>).sourceColumn
                val children = entityCache.getReferrers<Node>(node.id, sourceColumn)
                children?.toList() shouldBeEqualTo values
            }

            Node.all().with(Node::children).toList()

            checkChildrenReferences(child1, emptyList())
            checkChildrenReferences(child2, emptyList())
            checkChildrenReferences(root1, listOf(child1))
            checkChildrenReferences(root2, listOf(child1, child2))

            fun checkParentsReferences(node: Node, values: List<Node>) {
                val sourceColumn = (Node::parents
                    .apply { isAccessible = true }.getDelegate(node) as InnerTableLink<*, *, *, *>).sourceColumn
                val parents = entityCache.getReferrers<Node>(node.id, sourceColumn)
                parents?.toList() shouldBeEqualTo values
            }

            Node.all().with(Node::parents).toList()
            checkParentsReferences(child1, listOf(root1, root2))
            checkParentsReferences(child2, listOf(root2))
            checkParentsReferences(root1, emptyList())
            checkParentsReferences(root2, emptyList())
        }
    }

    class NodeOrdered(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<NodeOrdered>(NodesTable)

        var name by NodesTable.name
        var parents by NodeOrdered.via(NodeToNodes.child, NodeToNodes.parent)
        var children by NodeOrdered
            .via(NodeToNodes.parent, NodeToNodes.child) orderBy (NodesTable.name to SortOrder.ASC)

        override fun equals(other: Any?): Boolean = (other as? NodeOrdered)?.id == id
        override fun hashCode(): Int = Objects.hash(id)
        override fun toString(): String = "NodeOrdered($id)"
    }

    @Test
    fun `order by sized collection`() {
        withTables(NodesTable, NodeToNodes) {
            val root = NodeOrdered.new { name = "root" }
            listOf("#3", "#0", "#2", "#4", "#1").forEach() {
                NodeOrdered.new {
                    name = it
                    parents = SizedCollection(root)
                }
            }

            root.children.forEachIndexed { index, node ->
                node.name shouldBeEqualTo "#$index"
            }

            entityCache.clear(flush = true)

            root.children.map { it.name } shouldBeEqualTo listOf("#0", "#1", "#2", "#3", "#4")
        }
    }

    object Projects: IntIdTable("projects") {
        val name = varchar("name", 50)
    }

    object Tasks: IntIdTable("tasks") {
        val title = varchar("title", 64)
    }

    object ProjectTasks: CompositeIdTable("project_tasks") {
        val project = reference("project_id", Projects, onDelete = ReferenceOption.CASCADE)
        val task = reference("task_id", Tasks, onDelete = ReferenceOption.CASCADE)
        val approved = bool("approved").default(false)

        override val primaryKey = PrimaryKey(project, task)

        init {
            // Composite ID 를 정의한다.
            addIdColumn(project)
            addIdColumn(task)
        }
    }

    class Project(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Project>(Projects)

        var name by Projects.name
        var tasks by Task via ProjectTasks
    }

    class ProjectTask(id: EntityID<CompositeID>): CompositeEntity(id) {
        companion object: CompositeEntityClass<ProjectTask>(ProjectTasks)

        var approved by ProjectTasks.approved
    }

    class Task(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Task>(Tasks)

        var title by Tasks.title
        var approved by ProjectTasks.approved
    }

    @Test
    fun `additional link data using composite id inner table`() {
        withTables(Projects, Tasks, ProjectTasks) {
            val p1 = Project.new { name = "Project 1" }
            val p2 = Project.new { name = "Project 2" }

            val t1 = Task.new { title = "Task 1" }
            val t2 = Task.new { title = "Task 2" }
            val t3 = Task.new { title = "Task 3" }

            ProjectTask.new(
                CompositeID {
                    it[ProjectTasks.project] = p1.id
                    it[ProjectTasks.task] = t1.id
                }
            ) {
                approved = true
            }

            ProjectTask.new(
                CompositeID {
                    it[ProjectTasks.project] = p2.id
                    it[ProjectTasks.task] = t2.id
                }
            ) {
                approved = false
            }

            ProjectTask.new(
                CompositeID {
                    it[ProjectTasks.project] = p2.id
                    it[ProjectTasks.task] = t3.id
                }
            ) {
                approved = false
            }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1
                Project.all().with(Project::tasks).toList()
                val cache = TransactionManager.current().entityCache

                val p1Tasks = cache.getReferrers<Task>(p1.id, ProjectTasks.project)?.toList().orEmpty()
                p1Tasks.map { it.id } shouldBeEqualTo listOf(t1.id)
                p1Tasks.all { it.approved }.shouldBeTrue()

                val p2Tasks = cache.getReferrers<Task>(p2.id, ProjectTasks.project)?.toList().orEmpty()
                p2Tasks.map { it.id } shouldBeEqualTo listOf(t2.id, t3.id)
                p2Tasks.all { !it.approved }.shouldBeTrue()

                p1.tasks.count() shouldBeEqualTo 1
                p2.tasks.count() shouldBeEqualTo 2

                p1.tasks.single().approved shouldBeEqualTo true
                p2.tasks.map { it.approved } shouldBeEqualTo listOf(false, false)
            }
        }
    }
}
