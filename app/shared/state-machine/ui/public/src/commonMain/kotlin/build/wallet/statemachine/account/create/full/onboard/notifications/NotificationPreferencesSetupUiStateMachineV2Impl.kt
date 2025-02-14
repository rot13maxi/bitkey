package build.wallet.statemachine.account.create.full.onboard.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.wallet.analytics.events.EventTracker
import build.wallet.analytics.v1.Action.ACTION_APP_PUSH_NOTIFICATIONS_BITKEY_DISABLED
import build.wallet.analytics.v1.Action.ACTION_APP_PUSH_NOTIFICATIONS_BITKEY_ENABLED
import build.wallet.analytics.v1.Action.ACTION_APP_PUSH_NOTIFICATIONS_DISABLED
import build.wallet.analytics.v1.Action.ACTION_APP_PUSH_NOTIFICATIONS_ENABLED
import build.wallet.compose.coroutines.rememberStableCoroutineScope
import build.wallet.notifications.NotificationChannel
import build.wallet.notifications.NotificationTouchpointDao
import build.wallet.notifications.NotificationTouchpointType
import build.wallet.onboarding.OnboardingKeyboxStep
import build.wallet.onboarding.OnboardingKeyboxStep.CloudBackup
import build.wallet.onboarding.OnboardingKeyboxStepState.Complete
import build.wallet.onboarding.OnboardingKeyboxStepState.Incomplete
import build.wallet.onboarding.OnboardingKeyboxStepStateDao
import build.wallet.platform.settings.TelephonyCountryCodeProvider
import build.wallet.platform.settings.isCountry
import build.wallet.platform.web.InAppBrowserNavigator
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState.BottomSheetState
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState.BottomSheetState.ConfirmSkipRecoveryMethods
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState.BottomSheetState.NoEmailError
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState.OverlayState
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState.OverlayState.None
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState.PushAlertState
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState.SpecialState
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.ConfigureRecoveryOptionsUiState.SpecialState.SystemPromptRequestingPush
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.EnteringAndVerifyingEmailUiState
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.EnteringAndVerifyingPhoneNumberUiState
import build.wallet.statemachine.account.create.full.onboard.notifications.NotificationPreferencesSetupUiStateMachineV2Impl.RecoveryState.TransactionsAndProductUpdatesState
import build.wallet.statemachine.account.create.full.onboard.notifications.RecoveryChannelsSetupFormItemModel.State.Completed
import build.wallet.statemachine.account.create.full.onboard.notifications.RecoveryChannelsSetupFormItemModel.State.NotCompleted
import build.wallet.statemachine.core.InAppBrowserModel
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.SheetModel
import build.wallet.statemachine.notifications.NotificationPreferencesProps
import build.wallet.statemachine.notifications.NotificationPreferencesUiStateMachine
import build.wallet.statemachine.notifications.NotificationTouchpointInputAndVerificationProps
import build.wallet.statemachine.notifications.NotificationTouchpointInputAndVerificationProps.EntryPoint.Recovery
import build.wallet.statemachine.notifications.NotificationTouchpointInputAndVerificationUiStateMachine
import build.wallet.statemachine.platform.permissions.NotificationPermissionRequester
import build.wallet.ui.model.alert.AlertModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NotificationPreferencesSetupUiStateMachineV2Impl(
  private val eventTracker: EventTracker,
  private val notificationPermissionRequester: NotificationPermissionRequester,
  private val notificationTouchpointDao: NotificationTouchpointDao,
  private val notificationPreferencesUiStateMachine: NotificationPreferencesUiStateMachine,
  private val onboardingKeyboxStepStateDao: OnboardingKeyboxStepStateDao,
  private val notificationTouchpointInputAndVerificationUiStateMachine:
    NotificationTouchpointInputAndVerificationUiStateMachine,
  private val inAppBrowserNavigator: InAppBrowserNavigator,
  private val pushItemModelProvider: RecoveryChannelsSetupPushItemModelProvider,
  private val telephonyCountryCodeProvider: TelephonyCountryCodeProvider,
  private val uiErrorHintsProvider: UiErrorHintsProvider,
) : NotificationPreferencesSetupUiStateMachineV2 {
  @Composable
  @Suppress("CyclomaticComplexMethod")
  override fun model(props: NotificationPreferencesSetupUiPropsV2): ScreenModel {
    var state: RecoveryState by remember { mutableStateOf(ConfigureRecoveryOptionsUiState()) }
    val scope = rememberStableCoroutineScope()
    val smsErrorHint = uiErrorHintsProvider.errorHintFlow(UiErrorHintKey.Phone).collectAsState()

    val pushItemModel by remember {
      pushItemModelProvider.model(
        onShowAlert = { alertState ->
          state = ConfigureRecoveryOptionsUiState(PushAlertState(alertState))
        }
      )
    }.collectAsState(
      initial =
        RecoveryChannelsSetupFormItemModel(
          state = NotCompleted,
          uiErrorHint = UiErrorHint.None,
          onClick = {}
        )
    )

    var smsState by remember {
      mutableStateOf(NotCompleted)
    }
    var smsNumber by remember {
      mutableStateOf<String?>(null)
    }
    var emailState by remember {
      mutableStateOf(NotCompleted)
    }
    var emailAddress by remember {
      mutableStateOf<String?>(null)
    }

    LaunchedEffect("observe-stored-phone-number") {
      notificationTouchpointDao
        .phoneNumber()
        .collectLatest { storedPhoneNumber ->
          if (storedPhoneNumber != null) {
            smsState = Completed
            smsNumber = storedPhoneNumber.formattedDisplayValue
          }
        }
    }

    LaunchedEffect("observe-stored-email") {
      notificationTouchpointDao
        .email()
        .collectLatest { storedEmail ->
          if (storedEmail != null) {
            emailState = Completed
            emailAddress = storedEmail.value
          }
        }
    }

    return when (val currentState = state) {
      is ConfigureRecoveryOptionsUiState -> {
        // Deal with some special states outside of the ScreenModel domain
        handleSpecialOverlayState(
          overlayState = currentState.overlayState,
          setState = { state = it }
        )

        val isCountryUS = telephonyCountryCodeProvider.isCountry("us")

        // SMS is not allowed in the USA. We are hiding this in the onboarding flow, but
        // will be making it available in settings.
        RecoveryChannelsSetupFormBodyModel(
          pushItem = pushItemModel,
          smsItem = RecoveryChannelsSetupFormItemModel(
            state = smsState,
            displayValue = smsNumber,
            uiErrorHint = smsErrorHint.value,
            onClick =
              when (smsState) {
                Completed -> null
                else -> {
                  { state = EnteringAndVerifyingPhoneNumberUiState }
                }
              }
          ).takeIf { !isCountryUS },
          emailItem =
            RecoveryChannelsSetupFormItemModel(
              state = emailState,
              displayValue = emailAddress,
              uiErrorHint = UiErrorHint.None,
              onClick =
                when (emailState) {
                  Completed -> null
                  else -> {
                    { state = EnteringAndVerifyingEmailUiState }
                  }
                }
            ),
          alertModel = constructAlertModel(
            overlayState = currentState.overlayState,
            setState = { state = it },
            pushItemModel = pushItemModel
          ),
          bottomSheetModel = constructBottomSheetModel(
            currentState.overlayState,
            setState = { state = it }
          ),
          continueOnClick = {
            val allOptionsCompleted =
              (isCountryUS || smsState == Completed || smsErrorHint.value != UiErrorHint.None) &&
                emailState == Completed &&
                pushItemModel.state == Completed

            state = if (allOptionsCompleted) {
              TransactionsAndProductUpdatesState
            } else if (emailState != Completed) {
              ConfigureRecoveryOptionsUiState(overlayState = NoEmailError)
            } else {
              ConfigureRecoveryOptionsUiState(overlayState = ConfirmSkipRecoveryMethods)
            }
          },
          onBack = {
            scope.launch {
              onboardingKeyboxStepStateDao.setStateForStep(CloudBackup, Incomplete)
            }
          },
          learnOnClick = {
            if ((state as? ConfigureRecoveryOptionsUiState)?.overlayState == None) {
              state = RecoveryState.ShowLearnRecoveryWebView
            }
          }
        )
      }

      is EnteringAndVerifyingPhoneNumberUiState -> {
        notificationTouchpointInputAndVerificationUiStateMachine.model(
          props =
            NotificationTouchpointInputAndVerificationProps(
              fullAccountId = props.fullAccountId,
              fullAccountConfig = props.fullAccountConfig,
              touchpointType = NotificationTouchpointType.PhoneNumber,
              entryPoint = Recovery { state = ConfigureRecoveryOptionsUiState() },
              onClose = { state = ConfigureRecoveryOptionsUiState() }
            )
        )
      }

      EnteringAndVerifyingEmailUiState -> {
        notificationTouchpointInputAndVerificationUiStateMachine.model(
          props =
            NotificationTouchpointInputAndVerificationProps(
              fullAccountId = props.fullAccountId,
              fullAccountConfig = props.fullAccountConfig,
              touchpointType = NotificationTouchpointType.Email,
              entryPoint = Recovery(),
              onClose = { state = ConfigureRecoveryOptionsUiState() }
            )
        )
      }

      TransactionsAndProductUpdatesState -> {
        notificationPreferencesUiStateMachine.model(
          NotificationPreferencesProps(
            f8eEnvironment = props.fullAccountConfig.f8eEnvironment,
            fullAccountId = props.fullAccountId,
            onboardingRecoveryChannelsEnabled = setOfNotNull(
              NotificationChannel.Push.takeIf { pushItemModel.state == Completed },
              NotificationChannel.Sms.takeIf { smsState == Completed },
              NotificationChannel.Email // Always, currently
            ),
            onBack = { state = ConfigureRecoveryOptionsUiState() },
            source = props.source,
            onComplete = {
              scope.launch {
                onboardingKeyboxStepStateDao.setStateForStep(
                  OnboardingKeyboxStep.NotificationPreferences,
                  Complete
                )
                props.onComplete
              }
            }
          )
        )
      }

      RecoveryState.ShowLearnRecoveryWebView -> {
        InAppBrowserModel(
          open = {
            inAppBrowserNavigator.open(
              url = RECOVERY_INFO_URL,
              onClose = { state = ConfigureRecoveryOptionsUiState() }
            )
          }
        ).asModalScreen()
      }
    }
  }

  /**
   * Special overlays don't really fit neatly into our screen model and need special
   * handling.
   */
  @Composable
  private fun handleSpecialOverlayState(
    overlayState: OverlayState,
    setState: (RecoveryState) -> Unit,
  ) {
    when (overlayState) {
      !is SpecialState -> Unit
      is SystemPromptRequestingPush -> {
        notificationPermissionRequester.requestNotificationPermission(
          onGranted = {
            eventTracker.track(ACTION_APP_PUSH_NOTIFICATIONS_ENABLED)
            setState(ConfigureRecoveryOptionsUiState())
          },
          onDeclined = {
            eventTracker.track(ACTION_APP_PUSH_NOTIFICATIONS_DISABLED)
            setState(ConfigureRecoveryOptionsUiState())
          }
        )
      }
    }
  }

  /**
   * Create an alert model for the main screen
   */
  private fun constructAlertModel(
    overlayState: OverlayState,
    setState: (RecoveryState) -> Unit,
    pushItemModel: RecoveryChannelsSetupFormItemModel,
  ): AlertModel? =
    when (overlayState) {
      !is PushAlertState -> null
      else -> when (overlayState.pushActionState) {
        is RecoveryChannelsSetupPushActionState.AppInfoPromptRequestingPush -> {
          RequestPushAlertModel(
            onAllow = {
              setState(ConfigureRecoveryOptionsUiState(SystemPromptRequestingPush))
              eventTracker.track(ACTION_APP_PUSH_NOTIFICATIONS_BITKEY_ENABLED)
            },
            onDontAllow = {
              setState(ConfigureRecoveryOptionsUiState())
              eventTracker.track(ACTION_APP_PUSH_NOTIFICATIONS_BITKEY_DISABLED)
            }
          )
        }
        is RecoveryChannelsSetupPushActionState.OpenSettings -> {
          OpenSettingsForPushAlertModel(
            pushEnabled = pushItemModel.state == Completed,
            settingsOpenAction = {
              overlayState.pushActionState.openAction()
              setState(ConfigureRecoveryOptionsUiState())
            },
            onClose = { setState(ConfigureRecoveryOptionsUiState()) }
          )
        }
      }
    }

  /**
   * Create a bottom sheet for the main screen
   */
  private fun constructBottomSheetModel(
    overlayState: OverlayState,
    setState: (RecoveryState) -> Unit,
  ): SheetModel? =
    when (overlayState) {
      !is BottomSheetState -> null
      NoEmailError -> EmailRecoveryMethodRequiredErrorModal {
        setState(ConfigureRecoveryOptionsUiState())
      }
      ConfirmSkipRecoveryMethods -> ConfirmSkipRecoveryMethodsSheetModel(
        onCancel = { setState(ConfigureRecoveryOptionsUiState()) },
        onContinue = { setState(TransactionsAndProductUpdatesState) }
      )
    }

  private sealed interface RecoveryState {
    /**
     * Recovery options and status shown to user
     */
    data class ConfigureRecoveryOptionsUiState(
      val overlayState: OverlayState = None,
    ) : RecoveryState {
      sealed interface OverlayState {
        /** No overlaid info */
        data object None : OverlayState
      }

      /**
       * Showing BottomSheet instances
       */
      sealed interface BottomSheetState : OverlayState {
        /**
         * Email required for account setup before proceeding
         */
        data object NoEmailError : BottomSheetState

        /**
         * Prompt user to set up all available recovery methods, but allow them to continue if
         * minimum (email) added
         */
        data object ConfirmSkipRecoveryMethods : BottomSheetState
      }

      /**
       * Alerts related to push actions. Should result in AlertModel, but eventually should be
       * an app-specific dialog
       */
      data class PushAlertState(val pushActionState: RecoveryChannelsSetupPushActionState) :
        OverlayState

      /**
       * Showing something that needs special handling
       */
      sealed interface SpecialState : OverlayState {
        /** The system prompt to request push notifications */
        data object SystemPromptRequestingPush : SpecialState
      }
    }

    /** The customer is entering and verifying their phone number. */
    data object EnteringAndVerifyingPhoneNumberUiState : RecoveryState

    /** Entering email and going through the resulting verify flow. */
    data object EnteringAndVerifyingEmailUiState : RecoveryState

    /** Customer is selecting notification options */
    data object TransactionsAndProductUpdatesState : RecoveryState

    /** Show recovery info web page */
    data object ShowLearnRecoveryWebView : RecoveryState
  }
}

const val RECOVERY_INFO_URL = "https://bitkey.world/serious-about-security"
