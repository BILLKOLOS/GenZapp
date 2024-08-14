package org.thoughtcrime.securesms.jobs

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.recipients.Recipient
import org.whispersystems.GenZappservice.api.messages.GenZappServiceStoryMessageRecipient
import org.whispersystems.GenZappservice.api.messages.multidevice.SentTranscriptMessage
import org.whispersystems.GenZappservice.api.messages.multidevice.GenZappServiceSyncMessage
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Transmits a sent sync transcript to linked devices containing the story sync manifest for the given sent timestamp.
 * The transmitted message will contain all current recipients of a given story.
 */
class MultiDeviceStorySendSyncJob private constructor(parameters: Parameters, private val sentTimestamp: Long, private val deletedMessageId: Long) : BaseJob(parameters) {

  companion object {
    const val KEY = "MultiDeviceStorySendSyncJob"

    private val TAG = Log.tag(MultiDeviceStorySendSyncJob::class.java)

    private const val DATA_SENT_TIMESTAMP = "sent.timestamp"
    private const val DATA_DELETED_MESSAGE_ID = "deleted.message.id"

    @JvmStatic
    fun create(sentTimestamp: Long, deletedMessageId: Long): MultiDeviceStorySendSyncJob {
      return MultiDeviceStorySendSyncJob(
        parameters = Parameters.Builder()
          .addConstraint(NetworkConstraint.KEY)
          .setMaxAttempts(Parameters.UNLIMITED)
          .setLifespan(TimeUnit.DAYS.toMillis(1))
          .setQueue(KEY)
          .build(),
        sentTimestamp = sentTimestamp,
        deletedMessageId = deletedMessageId
      )
    }
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putLong(DATA_SENT_TIMESTAMP, sentTimestamp)
      .putLong(DATA_DELETED_MESSAGE_ID, deletedMessageId)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    val updateManifest = GenZappDatabase.storySends.getLocalManifest(sentTimestamp)
    val recipientsSet: Set<GenZappServiceStoryMessageRecipient> = updateManifest.toRecipientsSet()
    val transcriptMessage: GenZappServiceSyncMessage = GenZappServiceSyncMessage.forSentTranscript(buildSentTranscript(recipientsSet))
    val sendMessageResult = AppDependencies.GenZappServiceMessageSender.sendSyncMessage(transcriptMessage)

    Log.i(TAG, "Sent transcript message with ${recipientsSet.size} recipients")

    if (!sendMessageResult.isSuccess) {
      throw RetryableException()
    }

    GenZappDatabase.messages.deleteRemotelyDeletedStory(deletedMessageId)
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return e is RetryableException
  }

  private fun buildSentTranscript(recipientsSet: Set<GenZappServiceStoryMessageRecipient>): SentTranscriptMessage {
    return SentTranscriptMessage(
      Optional.of(GenZappServiceAddress(Recipient.self().requireAci())),
      sentTimestamp,
      Optional.empty(),
      0,
      emptyMap(),
      true,
      Optional.empty(),
      recipientsSet,
      Optional.empty()
    )
  }

  override fun onFailure() = Unit

  class RetryableException : Exception()

  class Factory : Job.Factory<MultiDeviceStorySendSyncJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): MultiDeviceStorySendSyncJob {
      val data = JsonJobData.deserialize(serializedData)
      return MultiDeviceStorySendSyncJob(
        parameters = parameters,
        sentTimestamp = data.getLong(DATA_SENT_TIMESTAMP),
        deletedMessageId = data.getLong(DATA_DELETED_MESSAGE_ID)
      )
    }
  }
}
