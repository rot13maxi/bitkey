package build.wallet.onboarding

import build.wallet.auth.LiteToFullAccountUpgrader
import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.hardware.HwKeyBundle
import build.wallet.bitkey.keybox.KeyCrossDraft
import build.wallet.bitkey.keybox.Keybox
import build.wallet.cloud.backup.CloudBackup
import build.wallet.cloud.backup.CloudBackupV2
import build.wallet.cloud.backup.LiteAccountCloudBackupRestorer
import build.wallet.keybox.keys.OnboardingAppKeyKeystore
import build.wallet.onboarding.LiteAccountBackupToFullAccountUpgrader.UpgradeError
import build.wallet.platform.random.Uuid
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.binding.binding
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.toErrorIfNull
import com.github.michaelbull.result.toResultOr

class LiteAccountBackupToFullAccountUpgraderImpl(
  private val liteAccountCloudBackupRestorer: LiteAccountCloudBackupRestorer,
  private val onboardingAppKeyKeystore: OnboardingAppKeyKeystore,
  private val onboardingKeyboxHardwareKeysDao: OnboardingKeyboxHardwareKeysDao,
  private val uuid: Uuid,
  private val liteToFullAccountUpgrader: LiteToFullAccountUpgrader,
) : LiteAccountBackupToFullAccountUpgrader {
  override suspend fun upgradeAccount(
    cloudBackup: CloudBackup,
    onboardingKeybox: Keybox,
  ): Result<FullAccount, UpgradeError> =
    binding {
      require(cloudBackup is CloudBackupV2) { "Unsupported cloud backup version" }

      val liteAccount =
        liteAccountCloudBackupRestorer.restoreFromBackup(cloudBackup)
          .mapError { UpgradeError("Failed to restore from backup", it) }
          .bind()
      val appKeyBundle =
        onboardingAppKeyKeystore.getAppKeyBundle(
          uuid.random(),
          liteAccount.config.bitcoinNetworkType
        ).toResultOr { UpgradeError("Missing onboarding app key bundle") }.bind()
      val hwKeys =
        onboardingKeyboxHardwareKeysDao.get()
          .mapError { UpgradeError("Failed to get onboarding keybox hw auth public key", it) }
          .toErrorIfNull { UpgradeError("Missing onboarding keybox hw auth public key") }
          .bind()
      val keyCross =
        KeyCrossDraft.WithAppKeysAndHardwareKeys(
          appKeyBundle = appKeyBundle,
          hardwareKeyBundle = HwKeyBundle(
            localId = uuid.random(),
            spendingKey = onboardingKeybox.activeSpendingKeyset.hardwareKey,
            authKey = hwKeys.hwAuthPublicKey,
            networkType = liteAccount.config.bitcoinNetworkType
          ),
          appGlobalAuthKeyHwSignature = hwKeys.appGlobalAuthKeyHwSignature,
          config = onboardingKeybox.config
        )
      liteToFullAccountUpgrader.upgradeAccount(liteAccount, keyCross)
        .mapError { UpgradeError("Failed to upgrade lite account", it) }
        .bind()
    }
}
