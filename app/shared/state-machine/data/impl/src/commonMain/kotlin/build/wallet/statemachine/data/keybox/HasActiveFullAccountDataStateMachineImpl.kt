package build.wallet.statemachine.data.keybox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.wallet.auth.AuthKeyRotationManager
import build.wallet.auth.InactiveDeviceIsEnabledFeatureFlag
import build.wallet.auth.PendingAuthKeyRotationAttempt
import build.wallet.bitcoin.wallet.SpendingWallet
import build.wallet.feature.isEnabled
import build.wallet.keybox.wallet.AppSpendingWalletProvider
import build.wallet.logging.log
import build.wallet.money.exchange.ExchangeRateSyncer
import build.wallet.recovery.socrec.PostSocRecTaskRepository
import build.wallet.recovery.socrec.PostSocialRecoveryTaskState.HardwareReplacementScreens
import build.wallet.recovery.socrec.PostSocialRecoveryTaskState.None
import build.wallet.recovery.socrec.TrustedContactKeyAuthenticator
import build.wallet.statemachine.data.keybox.AccountData.HasActiveFullAccountData
import build.wallet.statemachine.data.keybox.AccountData.HasActiveFullAccountData.ActiveFullAccountLoadedData
import build.wallet.statemachine.data.keybox.AccountData.HasActiveFullAccountData.LoadingActiveFullAccountData
import build.wallet.statemachine.data.keybox.AccountData.HasActiveFullAccountData.RotatingAuthKeys
import build.wallet.statemachine.data.keybox.address.FullAccountAddressDataProps
import build.wallet.statemachine.data.keybox.address.FullAccountAddressDataStateMachine
import build.wallet.statemachine.data.keybox.transactions.FullAccountTransactionsData.FullAccountTransactionsLoadedData
import build.wallet.statemachine.data.keybox.transactions.FullAccountTransactionsData.LoadingFullAccountTransactionsData
import build.wallet.statemachine.data.keybox.transactions.FullAccountTransactionsDataProps
import build.wallet.statemachine.data.keybox.transactions.FullAccountTransactionsDataStateMachine
import build.wallet.statemachine.data.mobilepay.MobilePayDataStateMachine
import build.wallet.statemachine.data.mobilepay.MobilePayProps
import build.wallet.statemachine.data.notifications.NotificationTouchpointDataStateMachine
import build.wallet.statemachine.data.notifications.NotificationTouchpointProps
import build.wallet.statemachine.data.recovery.losthardware.LostHardwareRecoveryDataStateMachine
import build.wallet.statemachine.data.recovery.losthardware.LostHardwareRecoveryProps
import com.github.michaelbull.result.get

class HasActiveFullAccountDataStateMachineImpl(
  private val mobilePayDataStateMachine: MobilePayDataStateMachine,
  private val fullAccountAddressDataStateMachine: FullAccountAddressDataStateMachine,
  private val fullAccountTransactionsDataStateMachine: FullAccountTransactionsDataStateMachine,
  private val lostHardwareRecoveryDataStateMachine: LostHardwareRecoveryDataStateMachine,
  private val notificationTouchpointDataStateMachine: NotificationTouchpointDataStateMachine,
  private val appSpendingWalletProvider: AppSpendingWalletProvider,
  private val exchangeRateSyncer: ExchangeRateSyncer,
  private val cloudBackupRefresher: CloudBackupRefresher,
  private val postSocRecTaskRepository: PostSocRecTaskRepository,
  private val authKeyRotationManager: AuthKeyRotationManager,
  private val inactiveDeviceIsEnabledFeatureFlag: InactiveDeviceIsEnabledFeatureFlag,
  private val trustedContactKeyAuthenticator: TrustedContactKeyAuthenticator,
) : HasActiveFullAccountDataStateMachine {
  @Composable
  override fun model(props: HasActiveFullAccountDataProps): HasActiveFullAccountData {
    LaunchedEffect("log-keybox-config", props.account.config) {
      log {
        "Loading active keybox for account ${props.account.accountId}, config ${props.account.config}"
      }
    }

    LaunchedEffect("sync rates") {
      exchangeRateSyncer.launchSync(scope = this)
    }

    LaunchedEffect("refresh cloud backups", props.account) {
      cloudBackupRefresher.refreshCloudBackupsWhenNecessary(scope = this, props.account)
    }

    LaunchedEffect("authenticate and endorse trusted contacts") {
      trustedContactKeyAuthenticator.backgroundAuthenticateAndEndorse(scope = this, props.account)
    }

    val addressData =
      fullAccountAddressDataStateMachine.model(FullAccountAddressDataProps(props.account))

    val notificationTouchpointData =
      notificationTouchpointDataStateMachine.model(
        props = NotificationTouchpointProps(props.account)
      )

    val recoveryIncompleteStatus = postSocRecTaskRepository.taskState.collectAsState(None)

    var spendingWallet by remember { mutableStateOf<SpendingWallet?>(null) }
    LaunchedEffect(props.account.keybox.activeSpendingKeyset) {
      spendingWallet =
        appSpendingWalletProvider.getSpendingWallet(props.account.keybox.activeSpendingKeyset).get()
    }

    if (inactiveDeviceIsEnabledFeatureFlag.isEnabled()) {
      // Using collectAsState stops and starts each recomposition because the returned flow can differ,
      // so we use produceState directly instead.
      val pendingAuthKeyRotationAttempt by produceState<PendingAuthKeyRotationAttempt?>(null, "observing pending attempts") {
        authKeyRotationManager.observePendingKeyRotationAttemptUntilNull()
          .collect { value = it }
      }

      // TODO: We should probably have a third "None" value, so that we can differentiate between
      //  loading and no pending attempt to mitigate any possible screen flashes.
      pendingAuthKeyRotationAttempt?.let {
        return RotatingAuthKeys(
          account = props.account,
          pendingAttempt = it
        )
      }
    }

    return when (val sw = spendingWallet) {
      null -> {
        LoadingActiveFullAccountData(props.account)
      }

      else -> {
        val transactionsData =
          fullAccountTransactionsDataStateMachine.model(
            FullAccountTransactionsDataProps(props.account, sw)
          )

        // Make sure the balance and transactions and auth tokens are loaded
        return when (transactionsData) {
          is LoadingFullAccountTransactionsData -> LoadingActiveFullAccountData(props.account)
          is FullAccountTransactionsLoadedData -> {
            val lostHardwareRecoveryData =
              lostHardwareRecoveryDataStateMachine.model(
                props =
                  LostHardwareRecoveryProps(
                    account = props.account,
                    props.hardwareRecovery
                  )
              )

            val mobilePayData =
              mobilePayDataStateMachine.model(
                MobilePayProps(
                  account = props.account,
                  spendingWallet = sw,
                  transactionsData = transactionsData,
                  fiatCurrency = props.currencyPreferenceData.fiatCurrencyPreference
                )
              )

            ActiveFullAccountLoadedData(
              account = props.account,
              spendingWallet = sw,
              addressData = addressData,
              transactionsData = transactionsData,
              mobilePayData = mobilePayData,
              lostHardwareRecoveryData = lostHardwareRecoveryData,
              notificationTouchpointData = notificationTouchpointData,
              isCompletingSocialRecovery = recoveryIncompleteStatus.value == HardwareReplacementScreens
            )
          }
        }
      }
    }
  }
}
