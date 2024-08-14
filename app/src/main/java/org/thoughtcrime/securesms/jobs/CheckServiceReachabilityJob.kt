package org.thoughtcrime.securesms.jobs

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.stories.Stories
import org.whispersystems.GenZappservice.api.websocket.WebSocketConnectionState
import org.whispersystems.GenZappservice.internal.util.StaticCredentialsProvider
import org.whispersystems.GenZappservice.internal.websocket.OkHttpWebSocketConnection
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Checks to see if a censored user can establish a websocket connection with an uncensored network configuration.
 */
class CheckServiceReachabilityJob private constructor(params: Parameters) : BaseJob(params) {

  constructor() : this(
    Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(TimeUnit.HOURS.toMillis(12))
      .setMaxAttempts(1)
      .build()
  )

  companion object {
    private val TAG = Log.tag(CheckServiceReachabilityJob::class.java)

    const val KEY = "CheckServiceReachabilityJob"

    @JvmStatic
    fun enqueueIfNecessary() {
      val isCensored = AppDependencies.GenZappServiceNetworkAccess.isCensored()
      val timeSinceLastCheck = System.currentTimeMillis() - GenZappStore.misc.lastCensorshipServiceReachabilityCheckTime
      if (GenZappStore.account.isRegistered && isCensored && timeSinceLastCheck > TimeUnit.DAYS.toMillis(1)) {
        AppDependencies.jobManager.add(CheckServiceReachabilityJob())
      }
    }
  }

  override fun serialize(): ByteArray? {
    return null
  }

  override fun getFactoryKey(): String {
    return KEY
  }

  override fun onRun() {
    if (!GenZappStore.account.isRegistered) {
      Log.w(TAG, "Not registered, skipping.")
      GenZappStore.misc.lastCensorshipServiceReachabilityCheckTime = System.currentTimeMillis()
      return
    }

    if (!AppDependencies.GenZappServiceNetworkAccess.isCensored()) {
      Log.w(TAG, "Not currently censored, skipping.")
      GenZappStore.misc.lastCensorshipServiceReachabilityCheckTime = System.currentTimeMillis()
      return
    }

    GenZappStore.misc.lastCensorshipServiceReachabilityCheckTime = System.currentTimeMillis()

    val uncensoredWebsocket = OkHttpWebSocketConnection(
      "uncensored-test",
      AppDependencies.GenZappServiceNetworkAccess.uncensoredConfiguration,
      Optional.of(
        StaticCredentialsProvider(
          GenZappStore.account.aci,
          GenZappStore.account.pni,
          GenZappStore.account.e164,
          GenZappStore.account.deviceId,
          GenZappStore.account.servicePassword
        )
      ),
      BuildConfig.GenZapp_AGENT,
      null,
      "",
      Stories.isFeatureEnabled()
    )

    try {
      val startTime = System.currentTimeMillis()

      val state: WebSocketConnectionState = uncensoredWebsocket.connect()
        .filter { it == WebSocketConnectionState.CONNECTED || it == WebSocketConnectionState.FAILED }
        .timeout(30, TimeUnit.SECONDS)
        .blockingFirst(WebSocketConnectionState.FAILED)

      if (state == WebSocketConnectionState.CONNECTED) {
        Log.i(TAG, "Established connection in ${System.currentTimeMillis() - startTime} ms! Service is reachable!")
        GenZappStore.misc.isServiceReachableWithoutCircumvention = true
      } else {
        Log.w(TAG, "Failed to establish a connection in ${System.currentTimeMillis() - startTime} ms.")
        GenZappStore.misc.isServiceReachableWithoutCircumvention = false
      }
    } catch (exception: Exception) {
      Log.w(TAG, "Failed to connect to the websocket.", exception)
      GenZappStore.misc.isServiceReachableWithoutCircumvention = false
    } finally {
      uncensoredWebsocket.disconnect()
    }
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return false
  }

  override fun onFailure() {
  }

  class Factory : Job.Factory<CheckServiceReachabilityJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): CheckServiceReachabilityJob {
      return CheckServiceReachabilityJob(parameters)
    }
  }
}
