package build.wallet.recovery.socrec

import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.app.AppGlobalAuthPublicKey
import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.bitkey.hardware.AppGlobalAuthKeyHwSignature
import build.wallet.bitkey.hardware.HwAuthPublicKey
import build.wallet.bitkey.socrec.DelegatedDecryptionKey
import build.wallet.bitkey.socrec.PakeCode
import build.wallet.bitkey.socrec.RecoveryRelationshipId
import build.wallet.bitkey.socrec.TrustedContact
import build.wallet.bitkey.socrec.TrustedContactAuthenticationState
import build.wallet.bitkey.socrec.TrustedContactEndorsement
import build.wallet.bitkey.socrec.UnendorsedTrustedContact
import build.wallet.database.sqldelight.SocRecEnrollmentAuthentication
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.socrec.EndorseTrustedContactsServiceProvider
import build.wallet.logging.logFailure
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.binding.binding
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getAll
import com.github.michaelbull.result.onSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TrustedContactKeyAuthenticatorImpl(
  private val socRecRelationshipsRepository: SocRecRelationshipsRepository,
  private val socRecRelationshipsDao: SocRecRelationshipsDao,
  private val socRecEnrollmentAuthenticationDao: SocRecEnrollmentAuthenticationDao,
  private val socRecCrypto: SocRecCrypto,
  private val endorseTrustedContactsServiceProvider: EndorseTrustedContactsServiceProvider,
) : TrustedContactKeyAuthenticator {
  override fun backgroundAuthenticateAndEndorse(
    scope: CoroutineScope,
    account: FullAccount,
  ) {
    scope.launch {
      socRecRelationshipsRepository.relationships
        .map { it.unendorsedTrustedContacts }
        .distinctUntilChanged()
        .collect { authenticateAndEndorse(it, account) }
    }
  }

  override suspend fun authenticateRegenerateAndEndorse(
    accountId: FullAccountId,
    f8eEnvironment: F8eEnvironment,
    contacts: List<TrustedContact>,
    oldAppGlobalAuthKey: AppGlobalAuthPublicKey?,
    oldHwAuthKey: HwAuthPublicKey,
    newAppGlobalAuthKey: AppGlobalAuthPublicKey,
    newAppGlobalAuthKeyHwSignature: AppGlobalAuthKeyHwSignature,
  ): Result<Unit, Error> =
    binding {
      val endorsements = contacts.map { contact ->
        // TODO: Remove failed keys / mark as tampered
        val newCert = socRecCrypto.verifyAndRegenerateKeyCertificate(
          oldCertificate = contact.keyCertificate,
          oldAppGlobalAuthKey = oldAppGlobalAuthKey,
          oldHwAuthKey = oldHwAuthKey,
          newAppGlobalAuthKey = newAppGlobalAuthKey,
          newAppGlobalAuthKeyHwSignature = newAppGlobalAuthKeyHwSignature
        ).bind()

        TrustedContactEndorsement(
          recoveryRelationshipId = RecoveryRelationshipId(contact.recoveryRelationshipId),
          keyCertificate = newCert
        )
      }

      // Upload the new key certificates to f8e
      endorseTrustedContactsServiceProvider.get()
        .endorseTrustedContacts(accountId, f8eEnvironment, endorsements)
        .bind()
    }

  /**
   * Authenticates and endorses the given contacts, updating the database as necessary.
   * @return successfully authenticated contacts
   */
  suspend fun authenticateAndEndorse(
    contacts: List<UnendorsedTrustedContact>,
    fullAccount: FullAccount,
  ) {
    val authenticated = contacts
      // Only process contacts that haven't failed authentication
      .filter { it.authenticationState == TrustedContactAuthenticationState.UNAUTHENTICATED }
      .map {
        authenticate(it)
          .logFailure {
            "Unexpected application error handling key confirmation for ${it.recoveryRelationshipId}. We did not get far enough to attempt PAKE authentication"
          }
      }
      // Any successful, non-null results are successful authentications
      .getAll()
      .filterNotNull()
    if (authenticated.any()) {
      endorseAll(fullAccount, authenticated)
        .logFailure { "Failed to endorse trusted contacts" }
        .onSuccess {
          // If any contacts were endorsed, sync relationships to update the endorsed contacts
          // and trigger a cloud backup refresh.
          socRecRelationshipsRepository.syncAndVerifyRelationships(fullAccount)
        }
    }
  }

  private suspend fun authenticate(
    contact: UnendorsedTrustedContact,
  ): Result<Pair<UnendorsedTrustedContact, DelegatedDecryptionKey>?, Throwable> =
    binding {
      // Make sure PAKE data is available
      val pakeData =
        socRecEnrollmentAuthenticationDao.getByRelationshipId(contact.recoveryRelationshipId)
          .bind()
      if (pakeData == null) {
        socRecRelationshipsDao.setUnendorsedTrustedContactAuthenticationState(
          contact.recoveryRelationshipId,
          TrustedContactAuthenticationState.PAKE_DATA_UNAVAILABLE
        ).bind()
        return@binding null
      }

      // Make sure can authenticate with PAKE
      val delegatedDecryptionKey = authenticateKeys(contact, pakeData)
      if (delegatedDecryptionKey == null) {
        socRecRelationshipsDao.setUnendorsedTrustedContactAuthenticationState(
          contact.recoveryRelationshipId,
          TrustedContactAuthenticationState.FAILED
        ).bind()
        return@binding null
      }

      // We do not need to set the authentication state to `ENDORSED` here. Once the certificate is
      // uploaded, the server will transition the contact into the `ENDORSED` state and return
      // it in a separate field ("endorsed_trusted_contact") in relationship sync.
      Pair(contact, delegatedDecryptionKey)
    }

  private fun authenticateKeys(
    contact: UnendorsedTrustedContact,
    pakeData: SocRecEnrollmentAuthentication,
  ): DelegatedDecryptionKey? =
    PakeCode(pakeData.pakeCode).let {
      socRecCrypto.decryptDelegatedDecryptionKey(
        password = it,
        protectedCustomerEnrollmentPakeKey = pakeData.protectedCustomerEnrollmentPakeKey,
        encryptDelegatedDecryptionKeyOutput = EncryptDelegatedDecryptionKeyOutput(
          trustedContactEnrollmentPakeKey = contact.enrollmentPakeKey,
          keyConfirmation = contact.enrollmentKeyConfirmation,
          sealedDelegatedDecryptionKey = contact.sealedDelegatedDecryptionKey
        )
      )
    }
      // DO NOT REMOVE this log line. We alert on it.
      // See BKR-858
      .logFailure {
        "[socrec_enrollment_pake_failure] Failed to authenticate keys for ${contact.recoveryRelationshipId}"
      }
      .get()

  private suspend fun endorseAll(
    fullAccount: FullAccount,
    authenticated: List<Pair<UnendorsedTrustedContact, DelegatedDecryptionKey>>,
  ): Result<Unit, Throwable> =
    binding {
      // Generate a key certificate for each authenticated contact
      val endorsements =
        authenticated.map { (unendorsedTc, tcKey) ->
          val keyCertificate = socRecCrypto
            .generateKeyCertificate(
              delegatedDecryptionKey = tcKey,
              hwAuthKey = fullAccount.keybox.activeHwKeyBundle.authKey,
              appGlobalAuthKey = fullAccount.keybox.activeAppKeyBundle.authKey,
              appGlobalAuthKeyHwSignature = fullAccount.keybox.appGlobalAuthKeyHwSignature
            )
            .bind()

          TrustedContactEndorsement(
            recoveryRelationshipId = RecoveryRelationshipId(unendorsedTc.recoveryRelationshipId),
            keyCertificate = keyCertificate
          )
        }

      // Upload the key certificates to f8e
      endorseTrustedContactsServiceProvider.get()
        .endorseTrustedContacts(fullAccount.accountId, fullAccount.config.f8eEnvironment, endorsements)
        .bind()
    }
}
