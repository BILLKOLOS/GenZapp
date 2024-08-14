package org.thoughtcrime.securesms.jobs

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.net.NotPushRegisteredException
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.GenZappservice.api.messages.multidevice.GenZappServiceSyncMessage
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException
import org.whispersystems.GenZappservice.api.push.exceptions.ServerRejectedException

/**
 * Sends a sync message to linked devices to notify them to refresh subscription status.
 */
class MultiDeviceSubscriptionSyncRequestJob private constructor(parameters: Parameters) : BaseJob(parameters) {

  companion object {
    const val KEY = "MultiDeviceSubscriptionSyncRequestJob"

    private val TAG = Log.tag(MultiDeviceSubscriptionSyncRequestJob::class.java)

    @JvmStatic
    fun enqueue() {
      val job = MultiDeviceSubscriptionSyncRequestJob(
        Parameters.Builder()
          .setQueue("MultiDeviceSubscriptionSyncRequestJob")
          .setMaxInstancesForFactory(2)
          .addConstraint(NetworkConstraint.KEY)
          .setMaxAttempts(10)
          .build()
      )

      AppDependencies.jobManager.add(job)
    }
  }

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  override fun onFailure() {
    Log.w(TAG, "Did not succeed!")
  }

  override fun onRun() {
    if (!Recipient.self().isRegistered) {
      throw NotPushRegisteredException()
    }

    if (!TextSecurePreferences.isMultiDevice(context)) {
      Log.i(TAG, "Not multi device, aborting...")
      return
    }

    val messageSender = AppDependencies.GenZappServiceMessageSender

    messageSender.sendSyncMessage(GenZappServiceSyncMessage.forFetchLatest(GenZappServiceSyncMessage.FetchType.SUBSCRIPTION_STATUS))
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return e is PushNetworkException && e !is ServerRejectedException
  }

  class Factory : Job.Factory<MultiDeviceSubscriptionSyncRequestJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): MultiDeviceSubscriptionSyncRequestJob {
      return MultiDeviceSubscriptionSyncRequestJob(parameters)
    }
  }
}
