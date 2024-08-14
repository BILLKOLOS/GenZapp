package org.GenZapp.benchmark

import android.content.Context
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.push.AccountManagerFactory
import org.thoughtcrime.securesms.util.RemoteConfig
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager
import org.whispersystems.GenZappservice.api.account.PreKeyUpload
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI
import org.whispersystems.GenZappservice.internal.configuration.GenZappServiceConfiguration
import java.io.IOException
import java.util.Optional

class DummyAccountManagerFactory : AccountManagerFactory() {
  override fun createAuthenticated(context: Context, aci: ACI, pni: PNI, number: String, deviceId: Int, password: String): GenZappServiceAccountManager {
    return DummyAccountManager(
      AppDependencies.GenZappServiceNetworkAccess.getConfiguration(number),
      aci,
      pni,
      number,
      deviceId,
      password,
      BuildConfig.GenZapp_AGENT,
      RemoteConfig.okHttpAutomaticRetry,
      RemoteConfig.groupLimits.hardLimit
    )
  }

  private class DummyAccountManager(configuration: GenZappServiceConfiguration?, aci: ACI?, pni: PNI?, e164: String?, deviceId: Int, password: String?, GenZappAgent: String?, automaticNetworkRetry: Boolean, maxGroupSize: Int) : GenZappServiceAccountManager(configuration, aci, pni, e164, deviceId, password, GenZappAgent, automaticNetworkRetry, maxGroupSize) {
    @Throws(IOException::class)
    override fun setGcmId(gcmRegistrationId: Optional<String>) {
    }

    @Throws(IOException::class)
    override fun setPreKeys(preKeyUpload: PreKeyUpload) {
    }
  }
}
