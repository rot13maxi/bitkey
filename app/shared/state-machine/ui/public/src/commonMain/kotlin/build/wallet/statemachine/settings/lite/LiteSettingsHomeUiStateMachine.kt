package build.wallet.statemachine.settings.lite

import build.wallet.bitkey.socrec.ProtectedCustomer
import build.wallet.money.display.CurrencyPreferenceData
import build.wallet.recovery.socrec.SocRecLiteAccountActions
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.StateMachine
import build.wallet.statemachine.data.firmware.FirmwareData
import build.wallet.statemachine.data.keybox.AccountData
import build.wallet.ui.model.status.StatusBannerModel
import kotlinx.collections.immutable.ImmutableList

interface LiteSettingsHomeUiStateMachine : StateMachine<LiteSettingsHomeUiProps, ScreenModel>

/**
 * @param homeStatusBannerModel status banner to show when showing all settings
 */
data class LiteSettingsHomeUiProps(
  val onBack: () -> Unit,
  val accountData: AccountData.HasActiveLiteAccountData,
  val currencyPreferenceData: CurrencyPreferenceData,
  val firmwareData: FirmwareData,
  val protectedCustomers: ImmutableList<ProtectedCustomer>,
  val homeStatusBannerModel: StatusBannerModel?,
  val socRecLiteAccountActions: SocRecLiteAccountActions,
)
