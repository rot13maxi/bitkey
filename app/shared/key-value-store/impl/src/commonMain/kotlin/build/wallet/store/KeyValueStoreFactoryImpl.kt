@file:OptIn(ExperimentalSettingsApi::class)

package build.wallet.store

import build.wallet.platform.PlatformContext
import build.wallet.platform.data.FileManager
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.coroutines.SuspendSettings

expect class KeyValueStoreFactoryImpl(
  platformContext: PlatformContext,
  fileManager: FileManager,
) : KeyValueStoreFactory {
  override suspend fun getOrCreate(storeName: String): SuspendSettings
}
