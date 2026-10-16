// SPDX-License-Identifier: Apache-2.0
package local.cardmin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Like the Dart json_serializable code of the original, request bodies always contain every key,
 * with explicit nulls. Responses tolerate unknown keys and unknown enum values.
 */
val ApiJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

// JSON shapes from client-spec/models.md + VERIFIED.md. Enums are kept as plain strings so
// unknown server values never break decoding.

// ---- request bodies ----

@Serializable data class LoginBody(val email: String, val password: String)
@Serializable data class EmailBody(val email: String)
@Serializable data class RefreshBody(val refreshToken: String)
@Serializable data class RegistrationCodeBody(val lastName: String, val registrationCode: String)
@Serializable data class RegisterBody(val email: String, val password: String, val registrationToken: String)
@Serializable data class ActivationBody(val activationToken: String)
@Serializable data class NewPasswordBody(
    val newPassword: String,
    val newPasswordConfirmation: String,
    val passwordResetToken: String,
)
@Serializable data class DeviceBody(val deviceName: String, val publicKey: String)
@Serializable data class DeviceConfirmBody(val challengeId: String, val signedText: String)
@Serializable data class RestrictedKeyBody(val key: String, val signature: String)
@Serializable data class PinBody(val encryptedPin: String, val keyId: String, val deviceId: String, val signature: String)
@Serializable data class ZipCodeBody(val zipCode: String)
@Serializable data class MobileNumberBody(val mobileNumber: String, val deviceId: String?)
@Serializable data class MobileNumberTanBody(val mobileNumber: String, val tan: String)
@Serializable data class MobileNumberDeleteConfirmBody(
    val changeRequestId: String,
    val tan: String? = null,
    val deviceId: String? = null,
    val signature: String? = null,
)
@Serializable data class ScaAuthorizationBody(val authenticateChangeRequestId: String, val deviceId: String?)
@Serializable data class ScaConfirmationBody(val authenticateChangeRequestId: String, val deviceId: String, val signature: String)
@Serializable data class ScaTanBody(val authenticateChangeRequestId: String, val tan: String)
@Serializable data class ScaDeclineBody(val challengeId: String)
@Serializable data class FraudCaseBody(val fraudCaseId: String, val responseType: String)

// ---- responses ----

@Serializable data class AuthTokens(
    val sessionToken: String,
    val refreshToken: String,
    val appUsers: List<Company> = emptyList(),
)

@Serializable data class Company(
    val companyName: String? = null,
    val companyId: Long? = null,
    val appUserId: Long? = null,
)

@Serializable data class RegistrationResponse(val registrationToken: String, val hasSolarisCard: Boolean = false)

@Serializable data class UserState(
    val isDeviceBound: Boolean = false,
    val hasCardBenefit: Boolean = false,
)

@Serializable data class Card(
    val id: Long,
    val line1: String? = null,
    val maskedPan: String? = null,
    val status: String = "",
    val quickActions: List<QuickAction> = emptyList(),
    val details: CardDetail? = null,
    val formattedExpirationDate: String? = null,
) {
    val isSetupCompleted get() = status !in setOf("", "NOT_DELIVERED", "CARD_SETUP_REQUIRED")
    val benefitType get() = details?.benefitType ?: ""
    fun has(type: String) = quickActions.any { it.quickActionType == type }
}

@Serializable data class CardDetail(
    val benefitType: String = "",
    val zipCode: String? = null,
    val maskedRegionZipCodes: String? = null,
    val zipCodeChangeType: String? = null,
)

@Serializable data class QuickAction(
    val quickActionType: String = "",
    val benefitType: String? = null,
    val url: String? = null,
)

/** GET /home – only the per-benefit balance is used (models.md, home_view_model.dart). */
@Serializable data class HomePage(val benefits: List<HomeBenefit> = emptyList())

@Serializable data class HomeBenefit(val availableAmount: Long? = null, val detail: HomeBenefitDetail? = null)

@Serializable data class HomeBenefitDetail(val benefitType: String = "", val cardId: Long? = null)

@Serializable data class StatementPeriod(val start: String, val end: String? = null)

@Serializable data class MobileNumber(val mobileNumber: String? = null, val verified: Boolean = false)
@Serializable data class MobileNumberDeleteResponse(val changeRequestId: String, val stringToSign: String? = null)

@Serializable data class Transactions(
    val transactions: List<Transaction?>? = null,
    val reservations: List<Transaction?>? = null,
    val cardBookingsMeta: Meta? = null,
)

@Serializable data class Transaction(
    val date: String? = null,
    val description: String? = null,
    val recipientName: String? = null,
    val senderName: String? = null,
    val isReservation: Boolean? = null,
    val amount: Amount? = null,
    val declineReason: String? = null,
)

@Serializable data class Amount(val currency: String = "EUR", val value: Long = 0)
@Serializable data class Meta(val nextPage: Int? = null)

@Serializable data class BoundDevice(
    val id: String? = null,
    val name: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable data class DeviceChallenge(
    val challengeId: String,
    val deviceId: String,
    val maskedMobileNumber: String? = null,
)

@Serializable data class StringToSign(val stringToSign: String)

/** GET /notifications: pending payment approvals and fraud cases. */
@Serializable data class PendingActions(
    val fraudCases: List<FraudCase> = emptyList(),
    val scaChallenges: List<ScaChallenge> = emptyList(),
)

@Serializable data class FraudCase(
    val merchantName: String? = null,
    val fraudCaseId: String,
    val respondUntil: String? = null,
    val amountCents: Long = 0,
)

@Serializable data class ScaChallenge(
    val merchantName: String? = null,
    val authenticateChangeRequestId: String,
    val challengedAt: String? = null,
    val challengeId: String,
    val amountCents: Long = 0,
)

/** GET /notification: notification centre. `detail` is polymorphic; we only read a few fields. */
@Serializable data class Notifications(val notifications: List<Notification> = emptyList())

@Serializable data class Notification(
    val id: Long,
    val timestamp: String? = null,
    val status: String = "",
    val detail: JsonObject? = null,
)

@Serializable data class ErrorResponse(val status: Int? = null, val type: String? = null)
