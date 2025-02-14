package build.wallet.statemachine.moneyhome.full

import build.wallet.f8e.socrec.SocRecRelationships
import build.wallet.money.FiatMoney
import build.wallet.money.currency.FiatCurrency
import build.wallet.recovery.socrec.SocRecFullAccountActions
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.SheetModel
import build.wallet.statemachine.core.StateMachine
import build.wallet.statemachine.data.firmware.FirmwareData
import build.wallet.statemachine.data.keybox.AccountData.HasActiveFullAccountData.ActiveFullAccountLoadedData
import build.wallet.ui.model.status.StatusBannerModel

/**
 * State machine for managing the Money Home experience for full (as opposed to lite)
 * customers. "Full" customers are customers that have a hardware device (and wallet),
 * and optionally are also a trusted contact for other "full" customers.
 */
interface MoneyHomeUiStateMachine : StateMachine<MoneyHomeUiProps, ScreenModel>

/**
 * @property convertedFiatBalance the balance in fiat to display (updated by parent state machine)
 * @property homeBottomSheetModel bottom sheet to show on root Money Home screen
 * @property homeStatusBannerModel status banner to show on root Money Home screen
 * @property onSettings Settings tab item clicked
 * @property origin The origin of money home. Used to control modals that only show during launch.
 */
data class MoneyHomeUiProps(
  val accountData: ActiveFullAccountLoadedData,
  val firmwareData: FirmwareData,
  val convertedFiatBalance: FiatMoney,
  val fiatCurrency: FiatCurrency,
  val socRecRelationships: SocRecRelationships,
  val socRecActions: SocRecFullAccountActions,
  val homeBottomSheetModel: SheetModel?,
  val homeStatusBannerModel: StatusBannerModel?,
  val onSettings: () -> Unit,
  val origin: Origin,
) {
  enum class Origin {
    Launch,
    Settings,
  }
}
