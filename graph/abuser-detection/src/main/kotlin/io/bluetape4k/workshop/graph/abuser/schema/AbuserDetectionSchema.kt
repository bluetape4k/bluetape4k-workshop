package io.bluetape4k.workshop.graph.abuser.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

// ────────────────────────────────────────────────────────────────────────────
// 정점 레이블
// ────────────────────────────────────────────────────────────────────────────

/**
 * 등록 사용자 계정의 정점 레이블입니다.
 *
 * ## 속성
 * - `userId` — 안정적인 사용자 식별자입니다(불투명 문자열, 예: UUID).
 * - `country` — ISO-3166-1 alpha-2 국가 코드입니다.
 */
object UserLabel : VertexLabel("User") {
    val userId = string("userId")
    val country = string("country")
}

/**
 * 디바이스 fingerprint 식별자의 정점 레이블입니다.
 *
 * ## 속성
 * - `deviceId` — 고유 디바이스 fingerprint입니다(불투명 문자열).
 * - `platform` — OS/platform 문자열입니다. 예: `"android"`, `"ios"`, `"web"`.
 */
object DeviceLabel : VertexLabel("Device") {
    val deviceId = string("deviceId")
    val platform = string("platform")
}

/**
 * IP 주소 식별자의 정점 레이블입니다.
 *
 * ## 속성
 * - `ip` — IPv4 또는 IPv6 주소 문자열입니다.
 */
object IpAddressLabel : VertexLabel("IpAddress") {
    val ip = string("ip")
}

/**
 * 전화번호 식별자의 정점 레이블입니다.
 *
 * **보안**: `phone`은 **E.164 형식 hash**(SHA-256 hex 또는 동등한 값)를 저장합니다.
 * 원시 전화번호는 그래프에 영속화하면 안 됩니다.
 * [io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService.addPhoneNumber] 호출 전에 해시 처리하는 책임은 호출자에게 있습니다.
 *
 * ## 속성
 * - `phone` — 해시 처리된 전화번호입니다(E.164 SHA-256 hex).
 */
object PhoneNumberLabel : VertexLabel("PhoneNumber") {
    val phone = string("phone")
}

/**
 * 결제 수단 식별자의 정점 레이블입니다.
 *
 * **보안**: `paymentToken`은 **PCI-safe processor token**을 저장합니다(예: Stripe/Braintree token).
 * 원시 PAN, CVV, 전체 카드 번호는 저장하면 안 됩니다.
 * 호출자는 [io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService.addPaymentMethod] 호출 전에 결제 processor에서 토큰화된 참조를 받아야 합니다.
 *
 * ## 속성
 * - `paymentToken` — processor가 발급한 결제 토큰입니다(원시 PAN 또는 CVV 아님).
 */
object PaymentMethodLabel : VertexLabel("PaymentMethod") {
    val paymentToken = string("paymentToken")
}

// ────────────────────────────────────────────────────────────────────────────
// 간선 레이블
// ────────────────────────────────────────────────────────────────────────────

/**
 * 사용자를 디바이스에 연결하는 간선 레이블입니다.
 *
 * ## 속성
 * - `occurredAt` — 이 디바이스에서 처음 로그인한 ISO-8601 타임스탬프입니다.
 */
object UsesDeviceLabel : EdgeLabel("USES_DEVICE", UserLabel, DeviceLabel) {
    val occurredAt = string("occurredAt")
}

/**
 * 사용자를 IP 주소에 연결하는 간선 레이블입니다.
 *
 * ## 속성
 * - `occurredAt` — 이 IP에서 처음 관측된 접속의 ISO-8601 타임스탬프입니다.
 */
object UsesIpLabel : EdgeLabel("USES_IP", UserLabel, IpAddressLabel) {
    val occurredAt = string("occurredAt")
}

/**
 * 사용자를 해시 처리된 전화번호에 연결하는 간선 레이블입니다.
 *
 * ## 속성
 * - `occurredAt` — 최초 연결 시점의 ISO-8601 타임스탬프입니다.
 */
object HasPhoneLabel : EdgeLabel("HAS_PHONE", UserLabel, PhoneNumberLabel) {
    val occurredAt = string("occurredAt")
}

/**
 * 사용자를 결제 수단 token에 연결하는 간선 레이블입니다.
 *
 * ## 속성
 * - `occurredAt` — 최초 결제 시도 시점의 ISO-8601 타임스탬프입니다.
 */
object UsesPaymentLabel : EdgeLabel("USES_PAYMENT", UserLabel, PaymentMethodLabel) {
    val occurredAt = string("occurredAt")
}

/**
 * 두 사용자 사이의 추천 관계를 기록하는 간선 레이블입니다.
 *
 * 방향: referrer → referred.
 * 이 간선은 어뷰저 클러스터 순회에서 의도적으로 **제외**됩니다. 추천 관계만으로는
 * 공유 신원을 뜻하지 않고, 공유 device/IP/phone/payment만 그 의미를 갖기 때문입니다.
 *
 * ## 속성
 * - `occurredAt` — 추천 이벤트의 ISO-8601 타임스탬프입니다.
 */
object ReferredByLabel : EdgeLabel("REFERRED_BY", UserLabel, UserLabel) {
    val occurredAt = string("occurredAt")
}
