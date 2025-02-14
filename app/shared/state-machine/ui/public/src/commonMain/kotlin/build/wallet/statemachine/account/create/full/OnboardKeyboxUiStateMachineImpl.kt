package build.wallet.statemachine.account.create.full

import androidx.compose.runtime.Composable
import build.wallet.analytics.events.screen.id.CloudEventTrackerScreenId.SAVE_CLOUD_BACKUP_FAILED
import build.wallet.analytics.events.screen.id.CloudEventTrackerScreenId.SAVE_CLOUD_BACKUP_LOADING
import build.wallet.analytics.events.screen.id.CreateAccountEventTrackerScreenId.LOADING_ONBOARDING_STEP
import build.wallet.analytics.events.screen.id.NotificationsEventTrackerScreenId.SAVE_NOTIFICATIONS_LOADING
import build.wallet.feature.isEnabled
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiProps
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiPropsV2
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachine
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2
import build.wallet.statemachine.cloud.FullAccountCloudSignInAndBackupProps
import build.wallet.statemachine.cloud.FullAccountCloudSignInAndBackupUiStateMachine
import build.wallet.statemachine.core.LoadingBodyModel
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.ScreenPresentationStyle
import build.wallet.statemachine.data.account.CreateFullAccountData
import build.wallet.statemachine.data.account.CreateFullAccountData.OnboardKeyboxDataFull.BackingUpKeyboxToCloudDataFull
import build.wallet.statemachine.data.account.CreateFullAccountData.OnboardKeyboxDataFull.CompletingCloudBackupDataFull
import build.wallet.statemachine.data.account.CreateFullAccountData.OnboardKeyboxDataFull.CompletingNotificationsDataFull
import build.wallet.statemachine.data.account.CreateFullAccountData.OnboardKeyboxDataFull.FailedCloudBackupDataFull
import build.wallet.statemachine.data.account.CreateFullAccountData.OnboardKeyboxDataFull.SettingNotificationsPreferencesDataFull
import build.wallet.statemachine.notifications.NotificationPreferencesProps.Source.Onboarding
import build.wallet.statemachine.settings.full.notifications.NotificationsFlowV2EnabledFeatureFlag

class OnboardKeyboxUiStateMachineImpl(
  private val fullAccountCloudSignInAndBackupUiStateMachine:
    FullAccountCloudSignInAndBackupUiStateMachine,
  private val notificationsFlowV2EnabledFeatureFlag: NotificationsFlowV2EnabledFeatureFlag,
  private val notificationPreferencesSetupUiStateMachine:
    NotificationPreferencesSetupUiStateMachine,
  private val notificationPreferencesSetupUiStateMachineV2:
    NotificationPreferencesSetupUiStateMachineV2,
) : OnboardKeyboxUiStateMachine {
  @Composable
  override fun model(props: OnboardKeyboxUiProps): ScreenModel {
    return when (props.onboardKeyboxData) {
      is CreateFullAccountData.OnboardKeyboxDataFull.LoadingInitialStepDataFull ->
        LoadingBodyModel(id = LOADING_ONBOARDING_STEP).asRootScreen()

      is BackingUpKeyboxToCloudDataFull ->
        BackingUpKeyboxScreen(props.onboardKeyboxData)

      is FailedCloudBackupDataFull ->
        FailedCloudBackupScreen(props.onboardKeyboxData)

      is CompletingCloudBackupDataFull ->
        LoadingBodyModel(id = SAVE_CLOUD_BACKUP_LOADING).asRootScreen()

      is SettingNotificationsPreferencesDataFull -> {
        if (notificationsFlowV2EnabledFeatureFlag.isEnabled()) {
          SettingNotificationsPreferencesScreenV2(props.onboardKeyboxData)
        } else {
          SettingNotificationsPreferencesScreen(props.onboardKeyboxData)
        }
      }

      is CompletingNotificationsDataFull ->
        LoadingBodyModel(id = SAVE_NOTIFICATIONS_LOADING).asRootScreen()
    }
  }

  @Composable
  private fun BackingUpKeyboxScreen(data: BackingUpKeyboxToCloudDataFull): ScreenModel {
    return fullAccountCloudSignInAndBackupUiStateMachine.model(
      props =
        FullAccountCloudSignInAndBackupProps(
          sealedCsek = data.sealedCsek,
          keybox = data.keybox,
          trustedContacts = emptyList(),
          onBackupFailed = data.onBackupFailed,
          onBackupSaved = data.onBackupSaved,
          onExistingCloudBackupFound = data.onExistingCloudBackupFound,
          presentationStyle = ScreenPresentationStyle.Root,
          isSkipCloudBackupInstructions = data.isSkipCloudBackupInstructions,
          requireAuthRefreshForCloudBackup = true
        )
    )
  }

  @Composable
  fun FailedCloudBackupScreen(data: FailedCloudBackupDataFull): ScreenModel {
    return CloudBackupFailedScreenModel(
      eventTrackerScreenId = SAVE_CLOUD_BACKUP_FAILED,
      onTryAgain = data.retry
    ).asRootScreen()
  }

  @Composable
  private fun SettingNotificationsPreferencesScreen(
    data: SettingNotificationsPreferencesDataFull,
  ): ScreenModel {
    return notificationPreferencesSetupUiStateMachine.model(
      props =
        NotificationPreferencesSetupUiProps(
          fullAccountId = data.keybox.fullAccountId,
          fullAccountConfig = data.keybox.config,
          onComplete = data.onComplete
        )
    )
  }

  @Composable
  private fun SettingNotificationsPreferencesScreenV2(
    data: SettingNotificationsPreferencesDataFull,
  ): ScreenModel {
    return notificationPreferencesSetupUiStateMachineV2.model(
      props =
        NotificationPreferencesSetupUiPropsV2(
          fullAccountId = data.keybox.fullAccountId,
          fullAccountConfig = data.keybox.config,
          source = Onboarding,
          onComplete = data.onComplete
        )
    )
  }
}
