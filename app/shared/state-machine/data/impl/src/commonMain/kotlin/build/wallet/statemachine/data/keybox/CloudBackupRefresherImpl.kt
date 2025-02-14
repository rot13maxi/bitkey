package build.wallet.statemachine.data.keybox

import build.wallet.analytics.events.EventTracker
import build.wallet.analytics.events.count.id.SocialRecoveryEventTrackerCounterId
import build.wallet.analytics.events.screen.EventTrackerCountInfo
import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.socrec.TrustedContact
import build.wallet.bitkey.socrec.TrustedContactAuthenticationState
import build.wallet.cloud.backup.CloudBackup
import build.wallet.cloud.backup.CloudBackupRepository
import build.wallet.cloud.backup.CloudBackupV2
import build.wallet.cloud.backup.FullAccountCloudBackupCreator
import build.wallet.cloud.backup.csek.SealedCsek
import build.wallet.cloud.backup.local.CloudBackupDao
import build.wallet.cloud.store.CloudStoreAccountRepository
import build.wallet.cloud.store.cloudServiceProvider
import build.wallet.logging.LogLevel
import build.wallet.logging.log
import build.wallet.logging.logFailure
import build.wallet.recovery.socrec.SocRecRelationshipsRepository
import build.wallet.statemachine.data.keybox.CloudBackupRefresherImpl.StoredBackupState.NeedsUpdate
import build.wallet.statemachine.data.keybox.CloudBackupRefresherImpl.StoredBackupState.UpToDate
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.binding.binding
import com.github.michaelbull.result.onSuccess
import com.github.michaelbull.result.toErrorIfNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

// TODO(BKR-933): merge into FullAccountCloudBackupRepairer
class CloudBackupRefresherImpl(
  private val socRecRelationshipsRepository: SocRecRelationshipsRepository,
  private val cloudBackupDao: CloudBackupDao,
  private val cloudStoreAccountRepository: CloudStoreAccountRepository,
  private val cloudBackupRepository: CloudBackupRepository,
  private val fullAccountCloudBackupCreator: FullAccountCloudBackupCreator,
  private val eventTracker: EventTracker,
  private val clock: Clock,
) : CloudBackupRefresher {
  private val lastCheckState: MutableStateFlow<Instant> = MutableStateFlow(Instant.DISTANT_PAST)

  override val lastCheck: StateFlow<Instant> = lastCheckState

  override suspend fun refreshCloudBackupsWhenNecessary(
    scope: CoroutineScope,
    fullAccount: FullAccount,
  ) {
    scope.launch {
      combine(
        socRecRelationshipsRepository.relationships
          // Only endorsed and verified trusted contacts are interesting for cloud backups.
          .map {
            it.trustedContacts
          }
          .distinctUntilChanged(),
        cloudBackupDao
          .backup(accountId = fullAccount.accountId.serverId)
          .distinctUntilChanged()
      ) { trustedContacts, cloudBackup ->
        binding {
          val storedBackupState =
            cloudBackup.getStoredBackupState(trustedContacts)
              .bind()

          when (storedBackupState) {
            UpToDate -> return@binding
            is NeedsUpdate -> {
              refreshCloudBackup(
                fullAccount = fullAccount,
                hwekEncryptedPkek = storedBackupState.hwekEncryptedPkek,
                trustedContacts = trustedContacts
              ).onSuccess {
                log { "Refreshed cloud backup" }
              }.bind()
            }
          }
        }
      }.collect {
        it.logFailure(LogLevel.Warn) {
          "Failed to refresh cloud backup"
        }.onSuccess {
          log { "Cloud backup check succeeded" }
        }
        lastCheckState.value = clock.now()
      }
    }
  }

  /** Type for determining what action to take regarding the stored cloud backup. */
  private sealed interface StoredBackupState {
    /** No need to update */
    data object UpToDate : StoredBackupState

    /** Update using the attached [SealedCsek] */
    data class NeedsUpdate(
      val hwekEncryptedPkek: SealedCsek,
    ) : StoredBackupState
  }

  /** returns the [StoredBackupState] indicating whether the cloud backup needs to be refreshed. */
  private fun CloudBackup?.getStoredBackupState(
    trustedContacts: List<TrustedContact>,
  ): Result<StoredBackupState, Error> {
    return when (this) {
      is CloudBackupV2 -> {
        val fields =
          fullAccountFields
            ?: return Err(Error("Lite Account Backups have no trusted contacts to refresh"))

        val backedUpRelationshipIds = fields.socRecSealedDekMap.keys
        val newRelationshipIds = trustedContacts.map { it.recoveryRelationshipId }.toSet()
        if (backedUpRelationshipIds == newRelationshipIds) {
          Ok(UpToDate)
        } else {
          val count: Int = trustedContacts.count {
            it.authenticationState == TrustedContactAuthenticationState.VERIFIED
          }

          eventTracker.track(
            EventTrackerCountInfo(
              eventTrackerCounterId = SocialRecoveryEventTrackerCounterId.SOCREC_COUNT_TOTAL_TCS,
              count = count
            )
          )

          Ok(
            NeedsUpdate(
              hwekEncryptedPkek = fields.sealedHwEncryptionKey
            )
          )
        }
      }
      null -> {
        // If the cloud backup is null we'll want to alert the customer, but for now
        // this is just treated like an error and logged.
        Err(Error("No cloud backup found"))
      }
    }
  }

  private suspend fun refreshCloudBackup(
    fullAccount: FullAccount,
    hwekEncryptedPkek: SealedCsek,
    trustedContacts: List<TrustedContact>,
  ): Result<Unit, Error> =
    binding {
      // Get the customer's cloud store account.
      val cloudStoreAccount =
        cloudStoreAccountRepository
          .currentAccount(cloudServiceProvider())
          .toErrorIfNull {
            // If the account is null we'll want to alert the customer, but for now
            // this is just treated like an error and logged.
            Error("Cloud store account not found")
          }
          .bind()

      // Create a new cloud backup.
      val cloudBackup =
        fullAccountCloudBackupCreator
          .create(
            keybox = fullAccount.keybox,
            sealedCsek = hwekEncryptedPkek,
            trustedContacts = trustedContacts
          )
          .bind()

      // Upload new cloud backup to the cloud.
      cloudBackupRepository.writeBackup(
        accountId = fullAccount.accountId,
        cloudStoreAccount = cloudStoreAccount,
        backup = cloudBackup,
        requireAuthRefresh = true
      ).bind()
    }
}
