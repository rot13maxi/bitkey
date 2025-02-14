package build.wallet.emergencyaccesskit

import build.wallet.bitcoin.AppPrivateKeyDao
import build.wallet.bitkey.account.FullAccountConfig
import build.wallet.bitkey.app.AppSpendingKeypair
import build.wallet.catching
import build.wallet.cloud.backup.csek.CsekDao
import build.wallet.emergencyaccesskit.EmergencyAccessPayloadRestorer.AccountRestoration
import build.wallet.emergencyaccesskit.EmergencyAccessPayloadRestorer.EmergencyAccessPayloadRestorerError
import build.wallet.emergencyaccesskit.EmergencyAccessPayloadRestorer.EmergencyAccessPayloadRestorerError.CsekMissing
import build.wallet.emergencyaccesskit.EmergencyAccessPayloadRestorer.EmergencyAccessPayloadRestorerError.DecryptionFailed
import build.wallet.emergencyaccesskit.EmergencyAccessPayloadRestorer.EmergencyAccessPayloadRestorerError.InvalidBackup
import build.wallet.encrypt.SymmetricKeyEncryptor
import build.wallet.f8e.F8eEnvironment
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.binding.binding
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.toErrorIfNull

class EmergencyAccessPayloadRestorerImpl(
  private val csekDao: CsekDao,
  private val symmetricKeyEncryptor: SymmetricKeyEncryptor,
  private val appPrivateKeyDao: AppPrivateKeyDao,
) : EmergencyAccessPayloadRestorer {
  override suspend fun restoreFromPayload(
    payload: EmergencyAccessKitPayload,
  ): Result<AccountRestoration, EmergencyAccessPayloadRestorerError> =
    binding {
      val decoder = EmergencyAccessKitPayloadDecoderImpl

      when (payload) {
        is EmergencyAccessKitPayload.EmergencyAccessKitPayloadV1 -> {
          val pkek =
            csekDao.get(payload.sealedHwEncryptionKey)
              .mapError { CsekMissing(cause = it) }
              .toErrorIfNull { CsekMissing() }
              .bind()

          val encodedBackup =
            Result.catching {
              symmetricKeyEncryptor.unseal(
                sealedData = payload.sealedActiveSpendingKeys,
                key = pkek.key
              )
            }
              .mapError { DecryptionFailed(cause = it) }
              .bind()

          val backup =
            decoder.decodeDecryptedBackup(encodedBackup)
              .mapError { InvalidBackup(cause = it) }
              .bind()

          // Load the spending app private key into the DAO.
          appPrivateKeyDao.storeAppSpendingKeyPair(
            AppSpendingKeypair(
              publicKey = backup.spendingKeyset.appKey,
              privateKey = backup.appSpendingKeyXprv
            )
          )
            .mapError {
              EmergencyAccessPayloadRestorerError.AppPrivateKeyStorageFailed(cause = it)
            }
            .bind()

          AccountRestoration(
            activeSpendingKeyset = backup.spendingKeyset,
            fullAccountConfig =
              FullAccountConfig(
                bitcoinNetworkType = backup.spendingKeyset.networkType,
                f8eEnvironment = F8eEnvironment.ForceOffline,
                isHardwareFake = false,
                isUsingSocRecFakes = false,
                isTestAccount = false
              )
          )
        }
      }
    }
}
