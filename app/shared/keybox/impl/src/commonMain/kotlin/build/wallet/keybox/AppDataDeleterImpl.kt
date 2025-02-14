package build.wallet.keybox

import build.wallet.account.AccountRepository
import build.wallet.auth.AuthKeyRotationAttemptDao
import build.wallet.auth.AuthTokenDao
import build.wallet.availability.F8eAuthSignatureStatusProvider
import build.wallet.bitcoin.AppPrivateKeyDao
import build.wallet.bitcoin.transactions.TransactionDetailDao
import build.wallet.bitcoin.transactions.TransactionPriorityPreference
import build.wallet.cloud.backup.csek.CsekDao
import build.wallet.cloud.backup.local.CloudBackupDao
import build.wallet.firmware.FirmwareDeviceInfoDao
import build.wallet.firmware.FirmwareMetadataDao
import build.wallet.fwup.FwupDataDao
import build.wallet.home.GettingStartedTaskDao
import build.wallet.home.HomeUiBottomSheetDao
import build.wallet.keybox.keys.OnboardingAppKeyKeystore
import build.wallet.limit.SpendingLimitDao
import build.wallet.logging.log
import build.wallet.money.display.BitcoinDisplayPreferenceRepository
import build.wallet.money.display.FiatCurrencyPreferenceRepository
import build.wallet.notifications.NotificationTouchpointDao
import build.wallet.onboarding.OnboardingKeyboxHardwareKeysDao
import build.wallet.onboarding.OnboardingKeyboxSealedCsekDao
import build.wallet.onboarding.OnboardingKeyboxStepStateDao
import build.wallet.platform.config.AppVariant
import build.wallet.platform.config.AppVariant.Customer
import build.wallet.recovery.RecoveryDao
import build.wallet.recovery.socrec.SocRecKeysDao
import build.wallet.recovery.socrec.SocRecRelationshipsRepository
import build.wallet.recovery.socrec.SocRecStartedChallengeDao
import com.github.michaelbull.result.coroutines.binding.binding

class AppDataDeleterImpl(
  private val appVariant: AppVariant,
  private val appPrivateKeyDao: AppPrivateKeyDao,
  private val accountRepository: AccountRepository,
  private val authTokenDao: AuthTokenDao,
  private val gettingStartedTaskDao: GettingStartedTaskDao,
  private val keyboxDao: KeyboxDao,
  private val notificationTouchpointDao: NotificationTouchpointDao,
  private val onboardingKeyboxSealedCsekDao: OnboardingKeyboxSealedCsekDao,
  private val onboardingKeyboxStepStateDao: OnboardingKeyboxStepStateDao,
  private val onboardingKeyboxHardwareKeysDao: OnboardingKeyboxHardwareKeysDao,
  private val spendingLimitDao: SpendingLimitDao,
  private val transactionDetailDao: TransactionDetailDao,
  private val fwupDataDao: FwupDataDao,
  private val firmwareDeviceInfoDao: FirmwareDeviceInfoDao,
  private val firmwareMetadataDao: FirmwareMetadataDao,
  private val transactionPriorityPreference: TransactionPriorityPreference,
  private val onboardingAppKeyKeystore: OnboardingAppKeyKeystore,
  private val fiatCurrencyPreferenceRepository: FiatCurrencyPreferenceRepository,
  private val homeUiBottomSheetDao: HomeUiBottomSheetDao,
  private val bitcoinDisplayPreferenceRepository: BitcoinDisplayPreferenceRepository,
  private val cloudBackupDao: CloudBackupDao,
  private val socRecKeysDao: SocRecKeysDao,
  private val socRecRelationshipsRepository: SocRecRelationshipsRepository,
  private val socRecStartedChallengeDao: SocRecStartedChallengeDao,
  private val csekDao: CsekDao,
  private val authKeyRotationAttemptDao: AuthKeyRotationAttemptDao,
  private val recoveryDao: RecoveryDao,
  private val authSignatureStatusProvider: F8eAuthSignatureStatusProvider,
) : AppDataDeleter {
  override suspend fun deleteAll() =
    binding {
      check(appVariant != Customer) {
        "Not allowed to delete app data in Customer builds."
      }
      appPrivateKeyDao.clear()
      gettingStartedTaskDao.clearTasks()
      notificationTouchpointDao.clear()
      onboardingKeyboxSealedCsekDao.clear()
      onboardingKeyboxStepStateDao.clear()
      onboardingKeyboxHardwareKeysDao.clear()
      spendingLimitDao.disableSpendingLimit()
      spendingLimitDao.removeAllLimits()
      transactionDetailDao.clear()
      fwupDataDao.clear()
      firmwareDeviceInfoDao.clear()
      firmwareMetadataDao.clear()
      authTokenDao.clear()
      transactionPriorityPreference.clear()
      onboardingAppKeyKeystore.clear()
      fiatCurrencyPreferenceRepository.clear()
      homeUiBottomSheetDao.clearHomeUiBottomSheet()
      bitcoinDisplayPreferenceRepository.clear()
      cloudBackupDao.clear()
      socRecKeysDao.clear()
      socRecRelationshipsRepository.clear()
      csekDao.clear()
      socRecStartedChallengeDao.clear()
      authKeyRotationAttemptDao.clear()
      recoveryDao.clear()
      authSignatureStatusProvider.clear()

      // Make sure we clear Account data last because this will transition the UI
      accountRepository.clear().bind()
      keyboxDao.clear().bind()

      log { "Successfully unpaired active keybox" }
    }
}
