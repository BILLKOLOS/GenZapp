package org.thoughtcrime.securesms.database

import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.GenZappservice.api.messages.GenZappServiceStoryMessageRecipient
import org.whispersystems.GenZappservice.api.push.DistributionId
import org.whispersystems.GenZappservice.api.push.ServiceId
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import org.whispersystems.GenZappservice.internal.push.SyncMessage

/**
 * Represents a list of, or update to a list of, who can access a story through what
 * distribution lists, and whether they can reply.
 */
data class SentStorySyncManifest(
  val entries: List<Entry>
) {

  /**
   * Represents an entry in the proto manifest.
   */
  data class Entry(
    val recipientId: RecipientId,
    val allowedToReply: Boolean = false,
    val distributionLists: List<DistributionId> = emptyList()
  )

  /**
   * Represents a flattened entry that is more convenient for detecting data changes.
   */
  data class Row(
    val recipientId: RecipientId,
    val messageId: Long,
    val allowsReplies: Boolean,
    val distributionId: DistributionId
  )

  fun getDistributionIdSet(): Set<DistributionId> {
    return entries.map { it.distributionLists }.flatten().toSet()
  }

  fun toRecipientsSet(): Set<GenZappServiceStoryMessageRecipient> {
    val recipients = Recipient.resolvedList(entries.map { it.recipientId })
    return recipients.map { recipient ->
      val serviceId = recipient.requireServiceId()
      val entry = entries.first { it.recipientId == recipient.id }

      GenZappServiceStoryMessageRecipient(
        GenZappServiceAddress(serviceId),
        entry.distributionLists.map { it.toString() },
        entry.allowedToReply
      )
    }.toSet()
  }

  fun flattenToRows(distributionIdToMessageIdMap: Map<DistributionId, Long>): Set<Row> {
    return entries.flatMap { getRowsForEntry(it, distributionIdToMessageIdMap) }.toSet()
  }

  private fun getRowsForEntry(entry: Entry, distributionIdToMessageIdMap: Map<DistributionId, Long>): List<Row> {
    return entry.distributionLists.map {
      Row(
        recipientId = entry.recipientId,
        allowsReplies = entry.allowedToReply,
        messageId = distributionIdToMessageIdMap[it] ?: -1L,
        distributionId = it
      )
    }.filterNot { it.messageId == -1L }
  }

  companion object {
    @WorkerThread
    @JvmStatic
    fun fromRecipientsSet(recipientsSet: Set<GenZappServiceStoryMessageRecipient>): SentStorySyncManifest {
      val entries = recipientsSet.map { recipient ->
        Entry(
          recipientId = RecipientId.from(recipient.GenZappServiceAddress),
          allowedToReply = recipient.isAllowedToReply,
          distributionLists = recipient.distributionListIds.map { DistributionId.from(it) }
        )
      }

      return SentStorySyncManifest(entries)
    }

    fun fromRecipientsSet(recipients: List<SyncMessage.Sent.StoryMessageRecipient>): SentStorySyncManifest {
      val entries = recipients.toSet().filter { it.destinationServiceId != null }.map { recipient ->
        Entry(
          recipientId = RecipientId.from(ServiceId.parseOrThrow(recipient.destinationServiceId!!)),
          allowedToReply = recipient.isAllowedToReply!!,
          distributionLists = recipient.distributionListIds.map { DistributionId.from(it) }
        )
      }

      return SentStorySyncManifest(entries)
    }
  }
}
