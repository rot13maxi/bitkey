package build.wallet.statemachine.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import build.wallet.auth.InactiveDeviceIsEnabledFeatureFlag
import build.wallet.availability.AppFunctionalityStatus
import build.wallet.availability.AppFunctionalityStatusProvider
import build.wallet.availability.FunctionalityFeatureStates.FeatureState.Available
import build.wallet.cloud.backup.CloudBackupHealthFeatureFlag
import build.wallet.feature.isEnabled
import build.wallet.statemachine.core.Icon
import build.wallet.statemachine.core.Icon.SmallIconAnnouncement
import build.wallet.statemachine.core.Icon.SmallIconBitkey
import build.wallet.statemachine.core.Icon.SmallIconCloud
import build.wallet.statemachine.core.Icon.SmallIconCurrency
import build.wallet.statemachine.core.Icon.SmallIconElectrum
import build.wallet.statemachine.core.Icon.SmallIconMobileLimit
import build.wallet.statemachine.core.Icon.SmallIconNotification
import build.wallet.statemachine.core.Icon.SmallIconPhone
import build.wallet.statemachine.core.Icon.SmallIconQuestion
import build.wallet.statemachine.core.Icon.SmallIconRecovery
import build.wallet.statemachine.core.Icon.SmallIconShieldPerson
import build.wallet.statemachine.settings.SettingsBodyModel.RowModel
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.BitkeyDevice
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.CloudBackupHealth
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.ContactUs
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.CurrencyPreference
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.CustomElectrumServer
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.HelpCenter
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.MobilePay
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.NotificationPreferences
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.Notifications
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.RecoveryChannels
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.RotateAuthKey
import build.wallet.statemachine.settings.SettingsListUiProps.SettingsListRow.TrustedContacts
import build.wallet.statemachine.settings.full.notifications.NotificationsFlowV2EnabledFeatureFlag
import build.wallet.statemachine.status.AppFunctionalityStatusAlertModel
import kotlinx.collections.immutable.toImmutableList
import kotlin.reflect.KClass

class SettingsListUiStateMachineImpl(
  private val appFunctionalityStatusProvider: AppFunctionalityStatusProvider,
  private val cloudBackupHealthFeatureFlag: CloudBackupHealthFeatureFlag,
  private val inactiveDeviceIsEnabledFeatureFlag: InactiveDeviceIsEnabledFeatureFlag,
  private val notificationsFlowV2EnabledFeatureFlag: NotificationsFlowV2EnabledFeatureFlag,
) : SettingsListUiStateMachine {
  @Composable
  override fun model(props: SettingsListUiProps): SettingsBodyModel {
    val cloudBackupHealthEnabled = remember { cloudBackupHealthFeatureFlag.isEnabled() }
    val inactiveDeviceIsEnabled = remember { inactiveDeviceIsEnabledFeatureFlag.isEnabled() }
    val appFunctionalityStatus =
      remember {
        appFunctionalityStatusProvider.appFunctionalityStatus(props.f8eEnvironment)
      }.collectAsState(AppFunctionalityStatus.FullFunctionality).value

    return SettingsBodyModel(
      onBack = props.onBack,
      sectionModels =
        listOfNotNull(
          SettingsSection(
            props = props,
            appFunctionalityStatus = appFunctionalityStatus,
            title = "General",
            rowTypes =
              listOfNotNull(
                MobilePay::class,
                BitkeyDevice::class,
                CurrencyPreference::class,
                if (notificationsFlowV2EnabledFeatureFlag.isEnabled()) {
                  NotificationPreferences::class
                } else {
                  Notifications::class
                }
              )
          ),
          SettingsSection(
            props = props,
            appFunctionalityStatus = appFunctionalityStatus,
            title = "Security & Recovery",
            rowTypes =
              listOfNotNull(
                RotateAuthKey::class.takeIf { inactiveDeviceIsEnabled },
                CloudBackupHealth::class.takeIf { cloudBackupHealthEnabled },
                TrustedContacts::class,
                RecoveryChannels::class.takeIf { notificationsFlowV2EnabledFeatureFlag.isEnabled() }
              )
          ),
          SettingsSection(
            props = props,
            appFunctionalityStatus = appFunctionalityStatus,
            title = "Advanced",
            rowTypes =
              listOfNotNull(
                CustomElectrumServer::class
              )
          ),
          SettingsSection(
            props = props,
            appFunctionalityStatus = appFunctionalityStatus,
            title = "Support",
            rowTypes =
              listOfNotNull(
                ContactUs::class,
                HelpCenter::class
              )
          )
        ).toImmutableList()
    )
  }

  @Composable
  fun SettingsSection(
    props: SettingsListUiProps,
    appFunctionalityStatus: AppFunctionalityStatus,
    title: String,
    @Suppress("UnstableCollections")
    rowTypes: List<KClass<out SettingsListRow>>,
  ): SettingsBodyModel.SectionModel? {
    // Build the row models based on if the parent wants to show the row for the section
    val rowModels =
      remember(appFunctionalityStatus) {
        rowTypes.mapNotNull { rowType ->
          props.supportedRows
            .firstOrNull { rowType.isInstance(it) }
            ?.rowModel(appFunctionalityStatus, props)
        }
      }

    if (rowModels.isEmpty()) return null

    return SettingsBodyModel.SectionModel(
      sectionHeaderTitle = title,
      rowModels = rowModels.toImmutableList()
    )
  }

  private fun SettingsListRow.rowModel(
    appFunctionalityStatus: AppFunctionalityStatus,
    props: SettingsListUiProps,
  ): RowModel {
    val (icon: Icon, title: String) =
      when (this) {
        is MobilePay -> Pair(SmallIconMobileLimit, "Mobile Pay")
        is BitkeyDevice -> Pair(SmallIconBitkey, "Bitkey Device")
        is CurrencyPreference -> Pair(SmallIconCurrency, "Currency")
        is Notifications, is NotificationPreferences -> Pair(SmallIconNotification, "Notifications")
        is RecoveryChannels -> Pair(SmallIconRecovery, "Recovery Methods")
        is CustomElectrumServer -> Pair(SmallIconElectrum, "Custom Electrum Server")
        is ContactUs -> Pair(SmallIconAnnouncement, "Contact Us")
        is HelpCenter -> Pair(SmallIconQuestion, "Help Center")
        is TrustedContacts -> Pair(SmallIconShieldPerson, "Trusted Contacts")
        is CloudBackupHealth -> Pair(SmallIconCloud, "Cloud Backup")
        is RotateAuthKey -> Pair(SmallIconPhone, "Mobile Devices")
      }
    val isRowEnabled = isRowEnabled(appFunctionalityStatus)
    return RowModel(
      icon = icon,
      title = title,
      isDisabled = !isRowEnabled,
      onClick = {
        if (isRowEnabled) {
          onClick()
        } else {
          when (appFunctionalityStatus) {
            is AppFunctionalityStatus.FullFunctionality -> Unit // Nothing to do
            is AppFunctionalityStatus.LimitedFunctionality ->
              props.onShowAlert(
                AppFunctionalityStatusAlertModel(
                  status = appFunctionalityStatus,
                  onDismiss = props.onDismissAlert
                )
              )
          }
        }
      }
    )
  }

  private fun SettingsListRow.isRowEnabled(
    appFunctionalityStatus: AppFunctionalityStatus,
  ): Boolean {
    return when (this) {
      // Rows that are always available
      is BitkeyDevice ->
        true

      is MobilePay ->
        appFunctionalityStatus.featureStates.mobilePay == Available
      is CurrencyPreference ->
        appFunctionalityStatus.featureStates.fiatExchangeRates == Available
      is Notifications, is NotificationPreferences, is RecoveryChannels ->
        appFunctionalityStatus.featureStates.notifications == Available
      is CustomElectrumServer ->
        appFunctionalityStatus.featureStates.customElectrumServer == Available
      is TrustedContacts ->
        appFunctionalityStatus.featureStates.securityAndRecovery == Available
      is HelpCenter ->
        appFunctionalityStatus.featureStates.helpCenter == Available
      is CloudBackupHealth ->
        appFunctionalityStatus.featureStates.cloudBackupHealth == Available
      is RotateAuthKey ->
        appFunctionalityStatus.featureStates.securityAndRecovery == Available
      is ContactUs ->
        appFunctionalityStatus.featureStates.helpCenter == Available
    }
  }
}
