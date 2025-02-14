package build.wallet.f8e.socrec.models

import build.wallet.bitkey.socrec.ProtectedCustomerEnrollmentPakeKey
import build.wallet.bitkey.socrec.TrustedContactAlias
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CreateTrustedContactInvitationRequestBody(
  @SerialName("trusted_contact_alias")
  val trustedContactAlias: TrustedContactAlias,
  @SerialName("protected_customer_enrollment_pake_pubkey")
  val protectedCustomerEnrollmentPakeKey: ProtectedCustomerEnrollmentPakeKey,
)
