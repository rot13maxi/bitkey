@file:OptIn(DelicateCoroutinesApi::class)

package build.wallet.integration.statemachine.recovery

import build.wallet.analytics.events.screen.id.CloudEventTrackerScreenId.CLOUD_SIGN_IN_LOADING
import build.wallet.analytics.events.screen.id.CloudEventTrackerScreenId.SAVE_CLOUD_BACKUP_INSTRUCTIONS
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_INITIATION_INSTRUCTIONS
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_PENDING
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_READY
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_ROTATING_AUTH_KEYS
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_BROADCASTING
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_SIGN_PSBTS_PROMPT
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_SUCCESS
import build.wallet.analytics.events.screen.id.DelayNotifyRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_ZERO_BALANCE
import build.wallet.analytics.events.screen.id.NotificationsEventTrackerScreenId.ENABLE_PUSH_NOTIFICATIONS
import build.wallet.bitkey.account.FullAccountConfig
import build.wallet.cloud.store.CloudStoreAccountFake.Companion.CloudStoreAccount1Fake
import build.wallet.cloud.store.cloudServiceProvider
import build.wallet.di.ActivityComponentImpl
import build.wallet.integration.statemachine.recovery.RecoveryTestingTrackerScreenId.RECOVERY_COMPLETED
import build.wallet.keybox.wallet.AppSpendingWalletProvider
import build.wallet.money.BitcoinMoney
import build.wallet.money.matchers.shouldBeGreaterThan
import build.wallet.statemachine.cloud.CloudSignInModelFake
import build.wallet.statemachine.core.LoadingSuccessBodyModel
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.test
import build.wallet.statemachine.recovery.inprogress.waiting.AppDelayNotifyInProgressBodyModel
import build.wallet.statemachine.ui.awaitUntilScreenWithBody
import build.wallet.statemachine.ui.clickPrimaryButton
import build.wallet.testing.AppTester
import build.wallet.testing.launchNewApp
import build.wallet.testing.relaunchApp
import build.wallet.testing.shouldBeLoaded
import build.wallet.testing.shouldBeOk
import com.github.michaelbull.result.getOrThrow
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.nondeterministic.eventuallyConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds

class LostAppAndCloudRecoveryFunctionalTests : FunSpec({
  lateinit var appTester: AppTester
  lateinit var app: ActivityComponentImpl
  lateinit var fullAccountConfig: FullAccountConfig
  lateinit var recoveryStateMachine: RecoveryTestingStateMachine
  lateinit var appSpendingWalletProvider: AppSpendingWalletProvider

  suspend fun setup(initWithTreasuryFunds: BitcoinMoney = BitcoinMoney.zero()) {
    appTester = launchNewApp()
    app = appTester.app
    appTester.onboardFullAccountWithFakeHardware()
    fullAccountConfig =
      appTester.getActiveFullAccount().keybox.config
        .copy(delayNotifyDuration = 2.seconds)
    if (initWithTreasuryFunds != BitcoinMoney.zero()) {
      val wallet = appTester.getActiveWallet()
      appTester.treasuryWallet.fund(wallet, initWithTreasuryFunds)
      appSpendingWalletProvider = appTester.app.appComponent.appSpendingWalletProvider
    }
    app.appDataDeleter.deleteAll().getOrThrow()
    app.cloudBackupDeleter.delete(cloudServiceProvider())
    appTester.deleteBackupsFromFakeCloud()
    recoveryStateMachine =
      RecoveryTestingStateMachine(
        app.accountDataStateMachine,
        app.recoveringKeyboxUiStateMachine,
        app.recoverySyncer,
        app.appComponent.accountRepository
      )
  }

  fun resetApp() {
    appTester = appTester.relaunchApp()
    app = appTester.app
    recoveryStateMachine =
      RecoveryTestingStateMachine(
        app.accountDataStateMachine,
        app.recoveringKeyboxUiStateMachine,
        app.recoverySyncer,
        app.appComponent.accountRepository
      )
  }

  test("delay & notify") {
    setup()
    val props = fullAccountConfig
    app.apply {
      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_INITIATION_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(ENABLE_PUSH_NOTIFICATIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<AppDelayNotifyInProgressBodyModel>(LOST_APP_DELAY_NOTIFY_PENDING)

        appTester.completeServerDelayNotifyPeriodForTesting(fullAccountConfig.f8eEnvironment)
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_READY)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_ROTATING_AUTH_KEYS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(SAVE_CLOUD_BACKUP_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<CloudSignInModelFake>(CLOUD_SIGN_IN_LOADING)
          .signInSuccess(CloudStoreAccount1Fake)
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_ZERO_BALANCE)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(RECOVERY_COMPLETED)
        cancelAndIgnoreRemainingEvents()
      }
    }
  }

  test("recovery lost app - force exiting in the middle of initiating") {
    setup()
    val props = fullAccountConfig
    app.apply {
      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_INITIATION_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(ENABLE_PUSH_NOTIFICATIONS)
          .clickPrimaryButton()
        cancelAndIgnoreRemainingEvents()
      }

      resetApp()

      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_INITIATION_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(ENABLE_PUSH_NOTIFICATIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<AppDelayNotifyInProgressBodyModel>(LOST_APP_DELAY_NOTIFY_PENDING)

        appTester.completeServerDelayNotifyPeriodForTesting(fullAccountConfig.f8eEnvironment)
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_READY)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_ROTATING_AUTH_KEYS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(SAVE_CLOUD_BACKUP_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<CloudSignInModelFake>(CLOUD_SIGN_IN_LOADING)
          .signInSuccess(CloudStoreAccount1Fake)
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_ZERO_BALANCE)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(RECOVERY_COMPLETED)
        cancelAndIgnoreRemainingEvents()
      }
    }
  }

  test("force exiting before cloud backup takes you back to icloud backup") {
    setup()
    val props = fullAccountConfig
    app.apply {
      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_INITIATION_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(ENABLE_PUSH_NOTIFICATIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<AppDelayNotifyInProgressBodyModel>(LOST_APP_DELAY_NOTIFY_PENDING)

        appTester.completeServerDelayNotifyPeriodForTesting(fullAccountConfig.f8eEnvironment)
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_READY)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_ROTATING_AUTH_KEYS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(SAVE_CLOUD_BACKUP_INSTRUCTIONS)
      }

      // Force quit!
      resetApp()

      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<FormBodyModel>(SAVE_CLOUD_BACKUP_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<CloudSignInModelFake>(CLOUD_SIGN_IN_LOADING)
          .signInSuccess(CloudStoreAccount1Fake)
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_ZERO_BALANCE)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(RECOVERY_COMPLETED)
        cancelAndIgnoreRemainingEvents()
      }
    }
  }

  test("force exiting after cloud backup & before sweep takes you back to sweep") {
    setup()
    val props = fullAccountConfig
    app.apply {
      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_INITIATION_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(ENABLE_PUSH_NOTIFICATIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<AppDelayNotifyInProgressBodyModel>(LOST_APP_DELAY_NOTIFY_PENDING)

        appTester.completeServerDelayNotifyPeriodForTesting(fullAccountConfig.f8eEnvironment)
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_READY)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_ROTATING_AUTH_KEYS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(SAVE_CLOUD_BACKUP_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<CloudSignInModelFake>(CLOUD_SIGN_IN_LOADING)
          .signInSuccess(CloudStoreAccount1Fake)
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        cancelAndIgnoreRemainingEvents()
      }

      resetApp()

      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_ZERO_BALANCE)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(RECOVERY_COMPLETED)
        cancelAndIgnoreRemainingEvents()
      }
    }
  }

  test("force exiting during D&N wait") {
    setup()
    val props = fullAccountConfig
    app.apply {
      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_INITIATION_INSTRUCTIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<FormBodyModel>(ENABLE_PUSH_NOTIFICATIONS)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<AppDelayNotifyInProgressBodyModel>(LOST_APP_DELAY_NOTIFY_PENDING)
        cancelAndIgnoreRemainingEvents()
      }

      resetApp()

      recoveryStateMachine.test(
        props = props,
        useVirtualTime = false,
        testTimeout = 20.seconds,
        turbineTimeout = 10.seconds
      ) {
        awaitUntilScreenWithBody<AppDelayNotifyInProgressBodyModel>(LOST_APP_DELAY_NOTIFY_PENDING)

        appTester.completeServerDelayNotifyPeriodForTesting(fullAccountConfig.f8eEnvironment)
        awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_READY)
          .clickPrimaryButton()
        awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_ROTATING_AUTH_KEYS) {
          state.shouldBe(LoadingSuccessBodyModel.State.Loading)
        }
        awaitUntilScreenWithBody<FormBodyModel>(SAVE_CLOUD_BACKUP_INSTRUCTIONS)
        cancelAndIgnoreRemainingEvents()
      }
    }
  }

  test("ensure funds are swept after recovery") {
    setup(BitcoinMoney.sats(10_000))
    val props = fullAccountConfig

    recoveryStateMachine.test(
      props = props,
      useVirtualTime = false,
      testTimeout = 60.seconds,
      turbineTimeout = 10.seconds
    ) {
      awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_INITIATION_INSTRUCTIONS)
        .clickPrimaryButton()
      awaitUntilScreenWithBody<FormBodyModel>(ENABLE_PUSH_NOTIFICATIONS)
        .clickPrimaryButton()
      awaitUntilScreenWithBody<AppDelayNotifyInProgressBodyModel>(LOST_APP_DELAY_NOTIFY_PENDING)

      appTester.completeServerDelayNotifyPeriodForTesting(fullAccountConfig.f8eEnvironment)
      awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_READY)
        .clickPrimaryButton()
      awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_ROTATING_AUTH_KEYS) {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }
      awaitUntilScreenWithBody<FormBodyModel>(SAVE_CLOUD_BACKUP_INSTRUCTIONS)
        .clickPrimaryButton()
      awaitUntilScreenWithBody<CloudSignInModelFake>(CLOUD_SIGN_IN_LOADING)
        .signInSuccess(CloudStoreAccount1Fake)
      awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS) {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_SIGN_PSBTS_PROMPT)
        .clickPrimaryButton()

      awaitUntilScreenWithBody<LoadingSuccessBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_BROADCASTING) {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      awaitUntilScreenWithBody<FormBodyModel>(LOST_APP_DELAY_NOTIFY_SWEEP_SUCCESS)
        .clickPrimaryButton()

      awaitUntilScreenWithBody<FormBodyModel>(RECOVERY_COMPLETED)

      eventually(
        eventuallyConfig {
          duration = 20.seconds
          interval = 1.seconds
          initialDelay = 1.seconds
        }
      ) {
        val activeAccount = appTester.getActiveFullAccount()
        val activeWallet =
          appSpendingWalletProvider.getSpendingWallet(
            activeAccount.keybox.activeSpendingKeyset
          ).getOrThrow()
        activeWallet.sync().shouldBeOk()
        val balance = activeWallet.balance().first().shouldBeLoaded()
        balance.total.shouldBeGreaterThan(BitcoinMoney.sats(0))
        // Eventually could iterate to calculate and subtract psbtsGeneratedData.totalFeeAmount)
        appTester.returnFundsToTreasury(activeAccount)
      }
    }
  }
})
