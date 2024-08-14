package org.thoughtcrime.securesms.releasechannel

import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.PointerAttachment
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.MediaUtil
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachment
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachmentPointer
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachmentRemoteId
import java.util.Optional
import java.util.UUID

/**
 * One stop shop for inserting Release Channel messages.
 */
object ReleaseChannel {

  fun insertReleaseChannelMessage(
    recipientId: RecipientId,
    body: String,
    threadId: Long,
    media: String? = null,
    mediaWidth: Int = 0,
    mediaHeight: Int = 0,
    mediaType: String = "image/webp",
    mediaAttachmentUuid: UUID? = UUID.randomUUID(),
    messageRanges: BodyRangeList? = null,
    storyType: StoryType = StoryType.NONE
  ): MessageTable.InsertResult? {
    val attachments: Optional<List<GenZappServiceAttachment>> = if (media != null) {
      val attachment = GenZappServiceAttachmentPointer(
        Cdn.S3.cdnNumber,
        GenZappServiceAttachmentRemoteId.S3,
        mediaType,
        null,
        Optional.empty(),
        Optional.empty(),
        mediaWidth,
        mediaHeight,
        Optional.empty(),
        Optional.empty(),
        0,
        Optional.of(media),
        false,
        false,
        MediaUtil.isVideo(mediaType),
        Optional.empty(),
        Optional.empty(),
        System.currentTimeMillis(),
        mediaAttachmentUuid
      )

      Optional.of(listOf(attachment))
    } else {
      Optional.empty()
    }

    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = recipientId,
      sentTimeMillis = System.currentTimeMillis(),
      serverTimeMillis = System.currentTimeMillis(),
      receivedTimeMillis = System.currentTimeMillis(),
      body = body,
      attachments = PointerAttachment.forPointers(attachments),
      serverGuid = UUID.randomUUID().toString(),
      messageRanges = messageRanges,
      storyType = storyType
    )

    return GenZappDatabase.messages.insertMessageInbox(message, threadId).orElse(null)
  }
}
