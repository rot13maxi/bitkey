package build.wallet.f8e.socrec

import build.wallet.bitkey.keys.app.AppKey
import build.wallet.bitkey.socrec.ProtectedCustomer
import build.wallet.bitkey.socrec.ProtectedCustomerAlias
import build.wallet.bitkey.socrec.TrustedContactEnrollmentPakeKey
import build.wallet.encrypt.XCiphertext
import build.wallet.f8e.socrec.models.AcceptTrustedContactInvitationRequestBody
import build.wallet.f8e.socrec.models.AcceptTrustedContactInvitationResponseBody
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

class AcceptTrustedContactInvitationServiceTests : FunSpec({
  test("Accept TC Invite - Request Serialization") {
    val request =
      AcceptTrustedContactInvitationRequestBody(
        code = "1234",
        customerAlias = "Some Alias",
        trustedContactEnrollmentPakeKey = TrustedContactEnrollmentPakeKey(AppKey.fromPublicKey("fake")),
        enrollmentPakeConfirmation = "enrollmentPakeConfirmation".encodeUtf8(),
        sealedDelegateDecryptionKey = XCiphertext("sealedDelegateDecryptionKey")
      )
    val result = Json.encodeToString(request)

    result.shouldEqualJson(
      """
      {
          "action": "Accept",
          "code": "1234",
          "customer_alias": "Some Alias",
          "trusted_contact_enrollment_pake_pubkey": "fake",
          "enrollment_pake_confirmation": "656e726f6c6c6d656e7450616b65436f6e6669726d6174696f6e",
          "sealed_delegated_decryption_pubkey": "sealedDelegateDecryptionKey"
      }
      """
    )
  }

  test("Accept TC Invite - Response Deserialization") {
    val response =
      """
      {
        "customer": {
          "customer_alias": "Some Alias",
          "recovery_relationship_id": "test-id"
        }
      }
      """.trimIndent()

    val result: AcceptTrustedContactInvitationResponseBody = Json.decodeFromString(response)

    result.shouldBeEqual(
      AcceptTrustedContactInvitationResponseBody(
        customer =
          ProtectedCustomer(
            alias = ProtectedCustomerAlias("Some Alias"),
            recoveryRelationshipId = "test-id"
          )
      )
    )
  }
})
