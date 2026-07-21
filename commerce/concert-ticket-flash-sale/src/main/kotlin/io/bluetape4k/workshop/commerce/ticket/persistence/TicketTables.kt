package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.spring.data.exposed.jdbc.annotation.ExposedEntity
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import java.util.UUID

object TicketSales : UUIDTable("ticket_sales", "sale_id") {
    val state = varchar("state", 24)
    val currentPolicyVersion = long("current_policy_version")
    val opensAt = timestamp("opens_at")
    val closesAt = timestamp("closes_at")
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
class TicketSaleEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TicketSaleEntity>(TicketSales)

    var state by TicketSales.state
    var currentPolicyVersion by TicketSales.currentPolicyVersion
    var opensAt by TicketSales.opensAt
    var closesAt by TicketSales.closesAt
    var revision by TicketSales.revision
    var createdAt by TicketSales.createdAt
    var updatedAt by TicketSales.updatedAt
}

object TicketSalePolicies : LongIdTable("ticket_sale_policy_versions") {
    val saleId = javaUUID("sale_id")
    val policyVersion = long("policy_version")
    val perUserLimit = integer("per_user_limit")
    val maxQuantity = integer("max_quantity")
    val holdSeconds = long("hold_seconds")
    val createdAt = timestamp("created_at")
}

@ExposedEntity
class TicketSalePolicyEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketSalePolicyEntity>(TicketSalePolicies)

    var saleId by TicketSalePolicies.saleId
    var policyVersion by TicketSalePolicies.policyVersion
    var perUserLimit by TicketSalePolicies.perUserLimit
    var maxQuantity by TicketSalePolicies.maxQuantity
    var holdSeconds by TicketSalePolicies.holdSeconds
    var createdAt by TicketSalePolicies.createdAt
}

object TicketInventories : LongIdTable("ticket_inventory") {
    val saleId = javaUUID("sale_id")
    val grade = varchar("grade", 32)
    val totalQuantity = integer("total_quantity")
    val heldQuantity = integer("held_quantity")
    val soldQuantity = integer("sold_quantity")
    val revision = long("revision")
}

@ExposedEntity
class TicketInventoryEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketInventoryEntity>(TicketInventories)

    var saleId by TicketInventories.saleId
    var grade by TicketInventories.grade
    var totalQuantity by TicketInventories.totalQuantity
    var heldQuantity by TicketInventories.heldQuantity
    var soldQuantity by TicketInventories.soldQuantity
    var revision by TicketInventories.revision

    fun toRecord() = InventoryRecord(saleId, grade, totalQuantity, heldQuantity, soldQuantity, revision)
}

object TicketIdentitySubjects : UUIDTable("ticket_identity_subjects", "subject_id") {
    val identityKind = varchar("identity_kind", 8)
    val createdAt = timestamp("created_at")
    val anonymizedAt = timestamp("anonymized_at").nullable()
}

@ExposedEntity
class TicketIdentitySubjectEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TicketIdentitySubjectEntity>(TicketIdentitySubjects)

    var identityKind by TicketIdentitySubjects.identityKind
    var createdAt by TicketIdentitySubjects.createdAt
    var anonymizedAt by TicketIdentitySubjects.anonymizedAt
}

object TicketIdentityAliases : LongIdTable("ticket_identity_aliases") {
    val identityKind = varchar("identity_kind", 8)
    val keyVersion = integer("key_version")
    val digest = binary("digest", 32)
    val subjectId = javaUUID("subject_id")
    val createdAt = timestamp("created_at")
}

@ExposedEntity
class TicketIdentityAliasEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketIdentityAliasEntity>(TicketIdentityAliases)

    var identityKind by TicketIdentityAliases.identityKind
    var keyVersion by TicketIdentityAliases.keyVersion
    var digest by TicketIdentityAliases.digest
    var subjectId by TicketIdentityAliases.subjectId
    var createdAt by TicketIdentityAliases.createdAt
}

object TicketWaitingRoomEntries : LongIdTable("ticket_waiting_room_entries") {
    val entryId = javaUUID("entry_id")
    val saleId = javaUUID("sale_id")
    val userSubjectId = javaUUID("user_subject_id")
    val state = varchar("state", 24)
    val sequence = long("sequence")
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
class TicketWaitingRoomEntryEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketWaitingRoomEntryEntity>(TicketWaitingRoomEntries)

    var entryId by TicketWaitingRoomEntries.entryId
    var saleId by TicketWaitingRoomEntries.saleId
    var userSubjectId by TicketWaitingRoomEntries.userSubjectId
    var state by TicketWaitingRoomEntries.state
    var sequence by TicketWaitingRoomEntries.sequence
    var revision by TicketWaitingRoomEntries.revision
    var createdAt by TicketWaitingRoomEntries.createdAt
    var updatedAt by TicketWaitingRoomEntries.updatedAt

    fun toRecord() = WaitingEntryRecord(id.value, entryId, saleId, userSubjectId, sequence)
}

object TicketAdmissionGrants : LongIdTable("ticket_admission_grants") {
    val saleId = javaUUID("sale_id")
    val grantNonce = javaUUID("grant_nonce")
    val buyerSubjectId = javaUUID("buyer_subject_id")
    val policyVersion = long("policy_version")
    val expiresAt = timestamp("expires_at")
    val consumedAttemptId = javaUUID("consumed_attempt_id").nullable()
    val consumedAt = timestamp("consumed_at").nullable()
    val createdAt = timestamp("created_at")
}

@ExposedEntity
class TicketAdmissionGrantEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketAdmissionGrantEntity>(TicketAdmissionGrants)

    var saleId by TicketAdmissionGrants.saleId
    var grantNonce by TicketAdmissionGrants.grantNonce
    var buyerSubjectId by TicketAdmissionGrants.buyerSubjectId
    var policyVersion by TicketAdmissionGrants.policyVersion
    var expiresAt by TicketAdmissionGrants.expiresAt
    var consumedAttemptId by TicketAdmissionGrants.consumedAttemptId
    var consumedAt by TicketAdmissionGrants.consumedAt
    var createdAt by TicketAdmissionGrants.createdAt
}

object TicketBuyerSaleStates : LongIdTable("ticket_buyer_sale_states") {
    val saleId = javaUUID("sale_id")
    val userSubjectId = javaUUID("user_subject_id")
    val policyVersion = long("policy_version")
    val purchasedQuantity = integer("purchased_quantity")
    val revision = long("revision")
}

@ExposedEntity
class TicketBuyerSaleStateEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketBuyerSaleStateEntity>(TicketBuyerSaleStates)

    var saleId by TicketBuyerSaleStates.saleId
    var userSubjectId by TicketBuyerSaleStates.userSubjectId
    var policyVersion by TicketBuyerSaleStates.policyVersion
    var purchasedQuantity by TicketBuyerSaleStates.purchasedQuantity
    var revision by TicketBuyerSaleStates.revision
}

object TicketPurchaseAttempts : UUIDTable("ticket_purchase_attempts", "attempt_id") {
    val saleId = javaUUID("sale_id")
    val userSubjectId = javaUUID("user_subject_id")
    val ipSubjectId = javaUUID("ip_subject_id")
    val grade = varchar("grade", 32)
    val quantity = integer("quantity")
    val policyVersion = long("policy_version")
    val state = varchar("state", 40)
    val holdDeadline = timestamp("hold_deadline")
    val authorizationOperationId = javaUUID("authorization_operation_id")
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
class TicketPurchaseAttemptEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TicketPurchaseAttemptEntity>(TicketPurchaseAttempts)

    var saleId by TicketPurchaseAttempts.saleId
    var userSubjectId by TicketPurchaseAttempts.userSubjectId
    var ipSubjectId by TicketPurchaseAttempts.ipSubjectId
    var grade by TicketPurchaseAttempts.grade
    var quantity by TicketPurchaseAttempts.quantity
    var policyVersion by TicketPurchaseAttempts.policyVersion
    var state by TicketPurchaseAttempts.state
    var holdDeadline by TicketPurchaseAttempts.holdDeadline
    var authorizationOperationId by TicketPurchaseAttempts.authorizationOperationId
    var revision by TicketPurchaseAttempts.revision
    var createdAt by TicketPurchaseAttempts.createdAt
    var updatedAt by TicketPurchaseAttempts.updatedAt
}

object TicketActiveIdentityGuards : LongIdTable("ticket_active_identity_guards") {
    val saleId = javaUUID("sale_id")
    val identityKind = varchar("identity_kind", 8)
    val identitySubjectId = javaUUID("identity_subject_id")
    val activeAttemptId = javaUUID("active_attempt_id")
    val createdAt = timestamp("created_at")
}

@ExposedEntity
class TicketActiveIdentityGuardEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketActiveIdentityGuardEntity>(TicketActiveIdentityGuards)

    var saleId by TicketActiveIdentityGuards.saleId
    var identityKind by TicketActiveIdentityGuards.identityKind
    var identitySubjectId by TicketActiveIdentityGuards.identitySubjectId
    var activeAttemptId by TicketActiveIdentityGuards.activeAttemptId
    var createdAt by TicketActiveIdentityGuards.createdAt
}

object TicketOrders : UUIDTable("ticket_orders", "order_id") {
    val attemptId = javaUUID("attempt_id")
    val saleId = javaUUID("sale_id")
    val grade = varchar("grade", 32)
    val quantity = integer("quantity")
    val state = varchar("state", 24)
    val ticketDisposition = varchar("ticket_disposition", 24)
    val authorizationOperationId = javaUUID("authorization_operation_id")
    val refundOperationId = javaUUID("refund_operation_id").nullable()
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
class TicketOrderEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TicketOrderEntity>(TicketOrders)

    var attemptId by TicketOrders.attemptId
    var saleId by TicketOrders.saleId
    var grade by TicketOrders.grade
    var quantity by TicketOrders.quantity
    var state by TicketOrders.state
    var ticketDisposition by TicketOrders.ticketDisposition
    var authorizationOperationId by TicketOrders.authorizationOperationId
    var refundOperationId by TicketOrders.refundOperationId
    var revision by TicketOrders.revision
    var createdAt by TicketOrders.createdAt
    var updatedAt by TicketOrders.updatedAt
}

object TicketPaymentOperations : LongIdTable("ticket_payment_operations") {
    val provider = varchar("provider", 32)
    val operationId = javaUUID("operation_id")
    val attemptId = javaUUID("attempt_id")
    val orderId = javaUUID("order_id").nullable()
    val operationKind = varchar("operation_kind", 16)
    val status = varchar("status", 32)
    val nextReconcileAt = timestamp("next_reconcile_at").nullable()
    val claimToken = javaUUID("claim_token").nullable()
    val claimRevision = long("claim_revision")
    val claimUntil = timestamp("claim_until").nullable()
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
class TicketPaymentOperationEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketPaymentOperationEntity>(TicketPaymentOperations)

    var provider by TicketPaymentOperations.provider
    var operationId by TicketPaymentOperations.operationId
    var attemptId by TicketPaymentOperations.attemptId
    var orderId by TicketPaymentOperations.orderId
    var operationKind by TicketPaymentOperations.operationKind
    var status by TicketPaymentOperations.status
    var nextReconcileAt by TicketPaymentOperations.nextReconcileAt
    var claimToken by TicketPaymentOperations.claimToken
    var claimRevision by TicketPaymentOperations.claimRevision
    var claimUntil by TicketPaymentOperations.claimUntil
    var revision by TicketPaymentOperations.revision
    var createdAt by TicketPaymentOperations.createdAt
    var updatedAt by TicketPaymentOperations.updatedAt
}

object TicketTickets : LongIdTable("ticket_tickets") {
    val orderId = javaUUID("order_id")
    val externalTicketDigest = binary("external_ticket_digest", 32).nullable()
    val state = varchar("state", 24)
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object TicketEffectOperations : LongIdTable("ticket_effect_operations") {
    val effectKind = varchar("effect_kind", 16)
    val operationId = javaUUID("operation_id")
    val orderId = javaUUID("order_id")
    val status = varchar("status", 24)
    val claimToken = javaUUID("claim_token").nullable()
    val claimRevision = long("claim_revision")
    val claimUntil = timestamp("claim_until").nullable()
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object TicketHttpIdempotencies : LongIdTable("ticket_http_idempotency") {
    val principalSubjectId = javaUUID("principal_subject_id")
    val httpMethod = varchar("http_method", 8)
    val canonicalRoute = varchar("canonical_route", 128)
    val resourceId = varchar("resource_id", 64)
    val operation = varchar("operation", 64)
    val idempotencyKeyDigest = binary("idempotency_key_digest", 32)
    val requestFingerprint = binary("request_fingerprint", 32)
    val status = varchar("status", 24)
    val attemptId = javaUUID("attempt_id").nullable()
    val responseStatus = integer("response_status").nullable()
    val responseBody = binary("response_body").nullable()
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@ExposedEntity
class TicketHttpIdempotencyEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TicketHttpIdempotencyEntity>(TicketHttpIdempotencies)

    var principalSubjectId by TicketHttpIdempotencies.principalSubjectId
    var httpMethod by TicketHttpIdempotencies.httpMethod
    var canonicalRoute by TicketHttpIdempotencies.canonicalRoute
    var resourceId by TicketHttpIdempotencies.resourceId
    var operation by TicketHttpIdempotencies.operation
    var idempotencyKeyDigest by TicketHttpIdempotencies.idempotencyKeyDigest
    var requestFingerprint by TicketHttpIdempotencies.requestFingerprint
    var status by TicketHttpIdempotencies.status
    var attemptId by TicketHttpIdempotencies.attemptId
    var responseStatus by TicketHttpIdempotencies.responseStatus
    var responseBody by TicketHttpIdempotencies.responseBody
    var expiresAt by TicketHttpIdempotencies.expiresAt
    var createdAt by TicketHttpIdempotencies.createdAt
    var updatedAt by TicketHttpIdempotencies.updatedAt
}

/** Tables that back the currently implemented repository boundaries. */
val ticketAuthorityTables = arrayOf(
    TicketSales,
    TicketSalePolicies,
    TicketInventories,
    TicketIdentitySubjects,
    TicketIdentityAliases,
    TicketWaitingRoomEntries,
    TicketAdmissionGrants,
    TicketBuyerSaleStates,
    TicketPurchaseAttempts,
    TicketActiveIdentityGuards,
    TicketOrders,
    TicketPaymentOperations,
    TicketHttpIdempotencies,
)
