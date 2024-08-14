package org.thoughtcrime.securesms.stories.viewer.reply.group

import org.GenZapp.paging.PagedDataSource
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient

class StoryGroupReplyDataSource(private val parentStoryId: Long) : PagedDataSource<MessageId, ReplyBody> {
  override fun size(): Int {
    return GenZappDatabase.messages.getNumberOfStoryReplies(parentStoryId)
  }

  override fun load(start: Int, length: Int, totalSize: Int, cancellationGenZapp: PagedDataSource.CancellationGenZapp): MutableList<ReplyBody> {
    val results: MutableList<ReplyBody> = ArrayList(length)
    GenZappDatabase.messages.getStoryReplies(parentStoryId).use { cursor ->
      cursor.moveToPosition(start - 1)
      val mmsReader = MessageTable.MmsReader(cursor)
      while (cursor.moveToNext() && cursor.position < start + length) {
        results.add(readRowFromRecord(mmsReader.getCurrent() as MmsMessageRecord))
      }
    }

    return results
  }

  override fun load(key: MessageId): ReplyBody {
    return readRowFromRecord(GenZappDatabase.messages.getMessageRecord(key.id) as MmsMessageRecord)
  }

  override fun getKey(data: ReplyBody): MessageId {
    return data.key
  }

  private fun readRowFromRecord(record: MmsMessageRecord): ReplyBody {
    val threadRecipient: Recipient = requireNotNull(GenZappDatabase.threads.getRecipientForThreadId(record.threadId))
    return when {
      record.isRemoteDelete -> ReplyBody.RemoteDelete(record)
      MessageTypes.isStoryReaction(record.type) -> ReplyBody.Reaction(record)
      else -> ReplyBody.Text(
        ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(AppDependencies.application, record, threadRecipient)
      )
    }
  }
}
