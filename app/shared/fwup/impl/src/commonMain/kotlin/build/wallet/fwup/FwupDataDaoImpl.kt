package build.wallet.fwup

import build.wallet.database.BitkeyDatabaseProvider
import build.wallet.database.sqldelight.FwupDataEntity
import build.wallet.db.DbError
import build.wallet.logging.logFailure
import build.wallet.map
import build.wallet.sqldelight.asFlowOfOneOrNull
import build.wallet.sqldelight.awaitTransaction
import build.wallet.unwrapLoadedValue
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okio.ByteString.Companion.toByteString

class FwupDataDaoImpl(
  private val databaseProvider: BitkeyDatabaseProvider,
) : FwupDataDao {
  private val database by lazy { databaseProvider.database() }

  override fun fwupData(): Flow<Result<FwupData?, DbError>> {
    return database
      .fwupDataQueries
      .getFwupData()
      .asFlowOfOneOrNull()
      .map { result ->
        result
          .map { value -> value.map { it?.toFwupData() } }
          .logFailure { "Failed to get fwup data" }
      }
      .unwrapLoadedValue()
      .distinctUntilChanged()
  }

  override suspend fun setFwupData(fwupData: FwupData): Result<Unit, DbError> {
    return database
      .awaitTransaction {
        fwupDataQueries.setFwupData(
          version = fwupData.version,
          chunkSize = fwupData.chunkSize.toLong(),
          signatureOffset = fwupData.signatureOffset.toLong(),
          appPropertiesOffset = fwupData.appPropertiesOffset.toLong(),
          firmware = fwupData.firmware.toByteArray(),
          signature = fwupData.signature.toByteArray(),
          fwupMode = fwupData.fwupMode
        )
      }
      .logFailure { "Failed to set fwup data" }
  }

  override suspend fun clear(): Result<Unit, DbError> {
    return database
      .awaitTransaction { fwupDataQueries.clear() }
      .logFailure { "Failed to clear fwup data" }
  }
}

private fun FwupDataEntity.toFwupData() =
  FwupData(
    version = version,
    chunkSize = chunkSize.toUInt(),
    signatureOffset = signatureOffset.toUInt(),
    appPropertiesOffset = appPropertiesOffset.toUInt(),
    firmware = firmware.toByteString(),
    signature = signature.toByteString(),
    fwupMode = fwupMode
  )
