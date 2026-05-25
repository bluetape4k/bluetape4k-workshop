package io.bluetape4k.workshop.graph.abuser.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

// ────────────────────────────────────────────────────────────────────────────
// Vertex Labels
// ────────────────────────────────────────────────────────────────────────────

/**
 * Vertex label for registered user accounts.
 *
 * ## Properties
 * - `userId` — stable user identifier (opaque string, e.g. UUID)
 * - `country` — ISO-3166-1 alpha-2 country code
 */
object UserLabel : VertexLabel("User") {
    val userId = string("userId")
    val country = string("country")
}

/**
 * Vertex label for device fingerprint identifiers.
 *
 * ## Properties
 * - `deviceId` — unique device fingerprint (opaque string)
 * - `platform` — OS/platform string, e.g. `"android"`, `"ios"`, `"web"`
 */
object DeviceLabel : VertexLabel("Device") {
    val deviceId = string("deviceId")
    val platform = string("platform")
}

/**
 * Vertex label for IP address identifiers.
 *
 * ## Properties
 * - `ip` — IPv4 or IPv6 address string
 */
object IpAddressLabel : VertexLabel("IpAddress") {
    val ip = string("ip")
}

/**
 * Vertex label for phone number identifiers.
 *
 * **Security**: `phone` stores an **E.164-format hash** (SHA-256 hex or equivalent).
 * Raw phone numbers MUST NOT be persisted in the graph.
 * Callers are responsible for hashing before calling [io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService.addPhoneNumber].
 *
 * ## Properties
 * - `phone` — hashed phone number (E.164 SHA-256 hex)
 */
object PhoneNumberLabel : VertexLabel("PhoneNumber") {
    val phone = string("phone")
}

/**
 * Vertex label for payment method identifiers.
 *
 * **Security**: `paymentToken` stores a **PCI-safe processor token** (e.g. Stripe/Braintree token).
 * Raw PAN, CVV, or full card numbers MUST NOT be stored.
 * Callers must obtain a tokenised reference from the payment processor before calling
 * [io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService.addPaymentMethod].
 *
 * ## Properties
 * - `paymentToken` — processor-issued payment token (never a raw PAN or CVV)
 */
object PaymentMethodLabel : VertexLabel("PaymentMethod") {
    val paymentToken = string("paymentToken")
}

// ────────────────────────────────────────────────────────────────────────────
// Edge Labels
// ────────────────────────────────────────────────────────────────────────────

/**
 * Edge label linking a user to a device.
 *
 * ## Properties
 * - `occurredAt` — ISO-8601 timestamp of the first login from this device
 */
object UsesDeviceLabel : EdgeLabel("USES_DEVICE", UserLabel, DeviceLabel) {
    val occurredAt = string("occurredAt")
}

/**
 * Edge label linking a user to an IP address.
 *
 * ## Properties
 * - `occurredAt` — ISO-8601 timestamp of the first observed connection from this IP
 */
object UsesIpLabel : EdgeLabel("USES_IP", UserLabel, IpAddressLabel) {
    val occurredAt = string("occurredAt")
}

/**
 * Edge label linking a user to a hashed phone number.
 *
 * ## Properties
 * - `occurredAt` — ISO-8601 timestamp of the first association
 */
object HasPhoneLabel : EdgeLabel("HAS_PHONE", UserLabel, PhoneNumberLabel) {
    val occurredAt = string("occurredAt")
}

/**
 * Edge label linking a user to a payment method token.
 *
 * ## Properties
 * - `occurredAt` — ISO-8601 timestamp of the first payment attempt
 */
object UsesPaymentLabel : EdgeLabel("USES_PAYMENT", UserLabel, PaymentMethodLabel) {
    val occurredAt = string("occurredAt")
}

/**
 * Edge label recording a referral relationship between two users.
 *
 * Direction: referrer → referred.
 * This edge is intentionally **excluded** from abuse-cluster traversal because referral
 * alone does not indicate shared identity — only shared device/IP/phone/payment does.
 *
 * ## Properties
 * - `occurredAt` — ISO-8601 timestamp of the referral event
 */
object ReferredByLabel : EdgeLabel("REFERRED_BY", UserLabel, UserLabel) {
    val occurredAt = string("occurredAt")
}
