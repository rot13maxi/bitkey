import build.wallet.gradle.logic.extensions.allTargets
import build.wallet.gradle.logic.gradle.exclude

plugins {
  id("build.wallet.kmp")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  allTargets()

  sourceSets {
    val androidMain by getting {
      dependencies {
        implementation(projects.core)
      }
    }

    commonMain {
      dependencies {
        api(libs.kmp.kotlin.datetime)
        api(projects.shared.availabilityPublic)
        api(projects.shared.authPublic)
        api(projects.shared.encryptionPublic)
        api(projects.shared.accountPublic)
        api(projects.shared.bdkBindingsPublic)
        api(projects.shared.cloudBackupPublic)
        api(projects.shared.datadogPublic)
        api(projects.shared.f8eClientPublic)
        api(projects.shared.keyboxPublic)
        api(projects.shared.keyValueStorePublic)
        api(projects.shared.ktorClientPublic)
        api(projects.shared.moneyPublic)
        api(projects.shared.nfcPublic)
        api(projects.shared.platformPublic)
        api(projects.shared.databasePublic)
        implementation(projects.shared.loggingPublic)
        implementation(libs.kmp.okio)
      }
    }
    commonTest {
      dependencies {
        implementation(projects.shared.accountFake)
        implementation(projects.shared.availabilityFake)
        implementation(projects.shared.bdkBindingsFake)
        implementation(projects.shared.bitcoinFake) {
          exclude(projects.shared.bitcoinPublic)
        }
        implementation(projects.shared.bitcoinTesting) {
          exclude(projects.shared.bitcoinPublic)
        }
        implementation(projects.shared.bitcoinPrimitivesFake)
        implementation(projects.shared.bitkeyPrimitivesFake)
        implementation(projects.shared.datadogFake)
        implementation(projects.shared.f8eClientFake)
        implementation(projects.shared.coroutinesTesting)
        implementation(projects.shared.keyboxFake)
        implementation(projects.shared.keyValueStoreFake)
        implementation(projects.shared.platformFake)
        implementation(projects.shared.sqldelightTesting)
        implementation(projects.shared.timeFake)
        implementation(projects.shared.moneyFake)
        implementation(projects.shared.featureFlagFake)
        implementation(projects.shared.testingPublic)
        implementation(libs.kmp.test.ktor.client.mock)
      }
    }

    val jvmIntegrationTest by getting {
      dependencies {
        implementation(libs.kmp.aws.secretsmanager)
        implementation(projects.shared.integrationTestingPublic)
      }
    }
  }
}
