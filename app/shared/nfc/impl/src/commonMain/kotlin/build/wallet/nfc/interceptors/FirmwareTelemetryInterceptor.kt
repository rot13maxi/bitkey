package build.wallet.nfc.interceptors

import build.wallet.firmware.FirmwareDeviceInfo
import build.wallet.firmware.FirmwareDeviceInfoDao
import build.wallet.firmware.FirmwareTelemetryUploader
import build.wallet.firmware.TelemetryIdentifiers
import build.wallet.logging.LogLevel.Warn
import build.wallet.logging.log
import build.wallet.nfc.NfcSession
import build.wallet.nfc.platform.NfcCommands
import build.wallet.toByteString
import com.github.michaelbull.result.onFailure
import okio.ByteString

/**
 * Collects firmware telemetry and (1) persists it locally and (2) uploads it to Memfault.
 */
fun collectFirmwareTelemetry(
  firmwareDeviceInfoDao: FirmwareDeviceInfoDao,
  firmwareTelemetryUploader: FirmwareTelemetryUploader,
) = NfcTransactionInterceptor { next ->
  val interceptor = FirmwareTelemetryInterceptor(firmwareDeviceInfoDao, firmwareTelemetryUploader)

  (
    { session, commands ->
      next(session, commands)

      // Only attempt to collect telemetry if the hardware is unlocked, because
      // the telemetry endpoints require authentication themselves.
      if (commands.queryAuthentication(session)) {
        val deviceInfo = commands.getDeviceInfo(session)
        interceptor.persistDeviceInfo(deviceInfo)
        interceptor.uploadTelemetry(deviceInfo, commands, session)
      }
    }
  )
}

private class FirmwareTelemetryInterceptor(
  private val firmwareDeviceInfoDao: FirmwareDeviceInfoDao,
  private val firmwareTelemetryUploader: FirmwareTelemetryUploader,
) {
  suspend fun persistDeviceInfo(deviceInfo: FirmwareDeviceInfo) {
    firmwareDeviceInfoDao.setDeviceInfo(deviceInfo)
      .onFailure { log(Warn, throwable = it) { "Unable to persist FirmwareDeviceInfo" } }
  }

  suspend fun uploadTelemetry(
    deviceInfo: FirmwareDeviceInfo,
    commands: NfcCommands,
    session: NfcSession,
  ) {
    val identifiers =
      TelemetryIdentifiers(
        serial = deviceInfo.serial,
        version = deviceInfo.version,
        swType = deviceInfo.swType,
        hwRevision = deviceInfo.hwRevision
      )

    getEvents(commands, session)?.let {
      firmwareTelemetryUploader.addEvents(it, identifiers)
    }

    getCoredump(commands, session)?.let {
      firmwareTelemetryUploader.addCoredump(it, identifiers)
    }
  }

  private suspend fun getEvents(
    commands: NfcCommands,
    session: NfcSession,
  ) = mutableListOf<UByte>().apply {
    while (true) {
      val events = commands.getEvents(session)
      addAll(events.fragment)
      if (events.remainingSize == 0) break
    }
  }.toByteString().let { if (it.size == 0) null else it }

  private suspend fun getCoredump(
    commands: NfcCommands,
    session: NfcSession,
  ): ByteString? {
    if (commands.getCoredumpCount(session) == 0) return null

    return mutableListOf<UByte>().apply {
      var offset = 0
      while (true) {
        val fragment = commands.getCoredumpFragment(session, offset)
        addAll(fragment.data)
        offset = fragment.offset
        if (fragment.complete) break
      }
    }.toByteString()
  }
}
