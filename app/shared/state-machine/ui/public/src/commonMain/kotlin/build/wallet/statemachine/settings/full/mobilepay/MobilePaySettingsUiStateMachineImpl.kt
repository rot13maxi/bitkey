package build.wallet.statemachine.settings.full.mobilepay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.wallet.limit.SpendingLimit
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.ScreenPresentationStyle.Root
import build.wallet.statemachine.limit.SetSpendingLimitUiStateMachine
import build.wallet.statemachine.limit.SpendingLimitProps
import build.wallet.statemachine.settings.full.mobilepay.MobilePaySettingsUiStateMachineImpl.State.SettingSpendingLimitUiState
import build.wallet.statemachine.settings.full.mobilepay.MobilePaySettingsUiStateMachineImpl.State.ShowingMobilePayStatusUiState

class MobilePaySettingsUiStateMachineImpl(
  private val mobilePayStatusUiStateMachine: MobilePayStatusUiStateMachine,
  private val setSpendingLimitUiStateMachine: SetSpendingLimitUiStateMachine,
) : MobilePaySettingsUiStateMachine {
  @Composable
  override fun model(props: MobilePaySettingsUiProps): ScreenModel {
    var state: State by remember { mutableStateOf(ShowingMobilePayStatusUiState) }

    return when (val currentState = state) {
      is ShowingMobilePayStatusUiState ->
        ScreenModel(
          body =
            mobilePayStatusUiStateMachine.model(
              props =
                MobilePayUiProps(
                  onBack = props.onBack,
                  accountData = props.accountData,
                  fiatCurrency = props.fiatCurrency,
                  onSetLimitClick = { currentLimit: SpendingLimit? ->
                    state = SettingSpendingLimitUiState(defaultSpendingLimit = currentLimit)
                  }
                )
            ),
          presentationStyle = Root
        )

      is SettingSpendingLimitUiState ->
        setSpendingLimitUiStateMachine.model(
          props =
            SpendingLimitProps(
              currentSpendingLimit = currentState.defaultSpendingLimit?.amount,
              onClose = { state = ShowingMobilePayStatusUiState },
              accountData = props.accountData,
              fiatCurrency = props.fiatCurrency,
              onSetLimit = {
                state = ShowingMobilePayStatusUiState
              }
            )
        )
    }
  }

  private sealed class State {
    data object ShowingMobilePayStatusUiState : State()

    data class SettingSpendingLimitUiState(
      val defaultSpendingLimit: SpendingLimit? = null,
    ) : State()
  }
}
