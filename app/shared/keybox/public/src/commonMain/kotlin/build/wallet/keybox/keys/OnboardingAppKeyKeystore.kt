package build.wallet.keybox.keys

import build.wallet.bitcoin.BitcoinNetworkType
import build.wallet.bitkey.app.AppGlobalAuthPublicKey
import build.wallet.bitkey.app.AppKeyBundle
import build.wallet.bitkey.app.AppRecoveryAuthPublicKey
import build.wallet.bitkey.app.AppSpendingPublicKey
import com.github.michaelbull.result.Result

/**
 * Onboarding app key keystore which
 */
interface OnboardingAppKeyKeystore {
  /**
   * Persist app public keys during onboarding.
   *
   * This is done to align with server contract which allows existing accounts to be returned when
   * the same keys are sent instead of creating a new one
   */
  suspend fun persistAppKeys(
    spendingKey: AppSpendingPublicKey,
    globalAuthKey: AppGlobalAuthPublicKey,
    recoveryAuthKey: AppRecoveryAuthPublicKey,
    bitcoinNetworkType: BitcoinNetworkType,
  )

  /**
   * Get app key bundle, returns null if no keys are persisted
   *
   * @param localId - the id to be associated with the [AppKeyBundle]
   * @param network - the [BitcoinNetworkType] associated with the keys
   */
  suspend fun getAppKeyBundle(
    localId: String,
    network: BitcoinNetworkType,
  ): AppKeyBundle?

  /**
   * Clear the app keys that were persisted during onboarding
   */
  suspend fun clear(): Result<Unit, Throwable>
}
