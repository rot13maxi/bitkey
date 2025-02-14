package build.wallet.ui.app.limit

import build.wallet.kotest.paparazzi.paparazziExtension
import build.wallet.statemachine.limit.MobilePayOnboardingScreenModel
import build.wallet.ui.app.core.form.FormScreen
import io.kotest.core.spec.style.FunSpec

class MobilePayOnboardingScreenSnapshots : FunSpec({
  val paparazzi = paparazziExtension()

  test("Mobile pay onboarding sheet model screen") {
    paparazzi.snapshot {
      FormScreen(
        MobilePayOnboardingScreenModel(
          onContinue = {},
          onSetUpLater = {},
          onClosed = {}
        )
      )
    }
  }
})
