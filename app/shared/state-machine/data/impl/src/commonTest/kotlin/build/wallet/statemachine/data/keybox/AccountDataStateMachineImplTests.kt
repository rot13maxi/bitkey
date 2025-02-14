package build.wallet.statemachine.data.keybox

import build.wallet.account.AccountRepositoryFake
import build.wallet.account.AccountStatus.NoAccount
import build.wallet.bitkey.factor.PhysicalFactor.App
import build.wallet.bitkey.keybox.FullAccountConfigMock
import build.wallet.bitkey.keybox.FullAccountMock
import build.wallet.bitkey.keybox.LiteAccountMock
import build.wallet.coroutines.turbine.turbines
import build.wallet.money.display.CurrencyPreferenceDataMock
import build.wallet.recovery.Recovery
import build.wallet.recovery.Recovery.Loading
import build.wallet.recovery.Recovery.NoActiveRecovery
import build.wallet.recovery.RecoverySyncerMock
import build.wallet.statemachine.StateMachineMock
import build.wallet.statemachine.core.test
import build.wallet.statemachine.data.account.OnboardConfigData
import build.wallet.statemachine.data.account.create.LoadedOnboardConfigDataMock
import build.wallet.statemachine.data.account.create.OnboardConfigDataStateMachine
import build.wallet.statemachine.data.keybox.AccountData.CheckingActiveAccountData
import build.wallet.statemachine.data.keybox.AccountData.HasActiveFullAccountData
import build.wallet.statemachine.data.keybox.AccountData.HasActiveFullAccountData.LoadingActiveFullAccountData
import build.wallet.statemachine.data.keybox.AccountData.NoActiveAccountData
import build.wallet.statemachine.data.keybox.config.TemplateFullAccountConfigData.LoadedTemplateFullAccountConfigData
import build.wallet.statemachine.data.recovery.conflict.NoLongerRecoveringData
import build.wallet.statemachine.data.recovery.conflict.NoLongerRecoveringDataStateMachine
import build.wallet.statemachine.data.recovery.conflict.NoLongerRecoveringDataStateMachineDataProps
import build.wallet.statemachine.data.recovery.conflict.SomeoneElseIsRecoveringData
import build.wallet.statemachine.data.recovery.conflict.SomeoneElseIsRecoveringDataProps
import build.wallet.statemachine.data.recovery.conflict.SomeoneElseIsRecoveringDataStateMachine
import com.github.michaelbull.result.Ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes

class AccountDataStateMachineImplTests : FunSpec({

  val accountRepository = AccountRepositoryFake()
  val recoverySyncerMock =
    RecoverySyncerMock(
      recovery = Loading,
      turbines::create
    )

  val hasActiveFullAccountDataStateMachine =
    object : HasActiveFullAccountDataStateMachine, StateMachineMock<HasActiveFullAccountDataProps, HasActiveFullAccountData>(
      LoadingActiveFullAccountData(FullAccountMock)
    ) {}
  val hasActiveLiteAccountDataStateMachine =
    object : HasActiveLiteAccountDataStateMachine, StateMachineMock<HasActiveLiteAccountDataProps, AccountData>(
      HasActiveLiteAccountDataFake
    ) {}
  val noActiveKeyboxDataStateMachine =
    object : NoActiveAccountDataStateMachine, StateMachineMock<NoActiveAccountDataProps, NoActiveAccountData>(
      NoActiveAccountData.CheckingRecoveryOrOnboarding
    ) {}

  val noLongerRecoveringDataStateMachine =
    object : NoLongerRecoveringDataStateMachine,
      StateMachineMock<NoLongerRecoveringDataStateMachineDataProps, NoLongerRecoveringData>(
        NoLongerRecoveringData.ShowingNoLongerRecoveringData(App, {})
      ) {}

  val someoneElseIsRecoveringDataStateMachine =
    object : SomeoneElseIsRecoveringDataStateMachine,
      StateMachineMock<SomeoneElseIsRecoveringDataProps, SomeoneElseIsRecoveringData>(
        SomeoneElseIsRecoveringData.ShowingSomeoneElseIsRecoveringData(App, {})
      ) {}

  val stateMachine =
    AccountDataStateMachineImpl(
      hasActiveFullAccountDataStateMachine = hasActiveFullAccountDataStateMachine,
      hasActiveLiteAccountDataStateMachine = hasActiveLiteAccountDataStateMachine,
      noActiveAccountDataStateMachine = noActiveKeyboxDataStateMachine,
      accountRepository = accountRepository,
      recoverySyncer = recoverySyncerMock,
      noLongerRecoveringDataStateMachine = noLongerRecoveringDataStateMachine,
      someoneElseIsRecoveringDataStateMachine = someoneElseIsRecoveringDataStateMachine,
      recoverySyncFrequency = 1.minutes,
      onboardConfigDataStateMachine =
        object : OnboardConfigDataStateMachine,
          StateMachineMock<Unit, OnboardConfigData>(
            LoadedOnboardConfigDataMock
          ) {}
    )

  val props =
    AccountDataProps(
      templateFullAccountConfigData =
        LoadedTemplateFullAccountConfigData(
          config = FullAccountConfigMock,
          updateConfig = {}
        ),
      currencyPreferenceData = CurrencyPreferenceDataMock
    )

  beforeTest {
    accountRepository.reset()
    hasActiveFullAccountDataStateMachine.reset()
    hasActiveLiteAccountDataStateMachine.reset()
    noActiveKeyboxDataStateMachine.reset()
  }

  test("no active keybox") {
    accountRepository.accountState.value = Ok(NoAccount)

    stateMachine.test(props) {
      awaitItem().shouldBe(CheckingActiveAccountData)
      recoverySyncerMock.recoveryStatus.value = Ok(NoActiveRecovery)
      awaitItem().shouldBe(NoActiveAccountData.CheckingRecoveryOrOnboarding)
    }
  }

  test("has active full account") {
    accountRepository.setActiveAccount(FullAccountMock)

    stateMachine.test(props) {
      awaitItem().shouldBe(CheckingActiveAccountData)

      awaitItem().shouldBe(LoadingActiveFullAccountData(FullAccountMock))
    }
  }

  test("has active lite account") {
    accountRepository.setActiveAccount(LiteAccountMock)

    stateMachine.test(props) {
      awaitItem().shouldBe(CheckingActiveAccountData)

      awaitItem().shouldBeInstanceOf<AccountData.HasActiveLiteAccountData>().also {
        it.account.shouldBe(LiteAccountMock)
      }
    }
  }

  test("NoLongerRecoveringData") {
    recoverySyncerMock.recoveryStatus.value = Ok(Recovery.NoLongerRecovering(App))
    accountRepository.setActiveAccount(FullAccountMock)

    stateMachine.test(props) {
      awaitItem().shouldBe(CheckingActiveAccountData)
      awaitItem().shouldBeInstanceOf<AccountData.NoLongerRecoveringFullAccountData>()
    }
  }

  test("SomeoneElseIsRecoveringData") {
    recoverySyncerMock.recoveryStatus.value = Ok(Recovery.SomeoneElseIsRecovering(App))
    accountRepository.setActiveAccount(FullAccountMock)

    stateMachine.test(props) {
      awaitItem().shouldBe(CheckingActiveAccountData)
      val item = awaitItem()
      item.shouldBeInstanceOf<AccountData.SomeoneElseIsRecoveringFullAccountData>()
      val accountData = item.data
      accountData.shouldBeInstanceOf<SomeoneElseIsRecoveringData.ShowingSomeoneElseIsRecoveringData>()
      accountData.cancelingRecoveryLostFactor.shouldBe(App)
    }
  }
})
