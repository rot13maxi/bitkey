plugins {
  id("build.wallet.android.lib")
  kotlin("android")
}

buildLogic {
  compose {
    composeUi()
  }

  test {
    snapshotTests()
  }
}

dependencies {
  api(projects.shared.amountPublic)
  api(projects.shared.uiCorePublic)
  api(libs.android.compose.ui.core)
  api(libs.android.voyager.navigator)
  api(libs.android.voyager.transitions)

  implementation(libs.android.accompanist.system.ui.controller)
  implementation(libs.android.compose.ui.material)
  implementation(libs.android.compose.ui.material3)
  implementation(libs.kmp.kotlin.datetime)
  implementation(libs.jvm.zxing)
  implementation(libs.android.io.coil.compose)
  implementation(libs.android.io.coil.svg)
  implementation(libs.android.lottie.compose)
  implementation(projects.shared.stateMachineUiPublic)
}
