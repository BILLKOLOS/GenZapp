package org.thoughtcrime.securesms.conversation.quotes

import android.app.Application
import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Observable
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.ConversationMessage.ConversationMessageFactory
import org.thoughtcrime.securesms.conversation.v2.data.AttachmentHelper
import org.thoughtcrime.securesms.conversation.v2.data.ReactionHelper
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.Quote
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.util.getQuote

class MessageQuotesRepository {

  companion object {
    private val TAG = Log.tag(MessageQuotesRepository::class.java)
  }

  /**
   * Retrieves all messages that quote the target message, as well as any messages that quote _those_ messages, recursively.
   */
  fun getMessagesInQuoteChain(application: Application, messageId: MessageId): Observable<List<ConversationMessage>> {
    return Observable.create { emitter ->
      val threadId: Long = GenZappDatabase.messages.getThreadIdForMessage(messageId.id)
      if (threadId < 0) {
        Log.w(TAG, "Could not find a threadId for $messageId!")
        emitter.onNext(emptyList())
        return@create
      }

      val databaseObserver: DatabaseObserver = AppDependencies.databaseObserver
      val observer = DatabaseObserver.Observer { emitter.onNext(getMessagesInQuoteChainSync(application, messageId)) }

      databaseObserver.registerConversationObserver(threadId, observer)

      emitter.setCancellable { databaseObserver.unregisterObserver(observer) }
      emitter.onNext(getMessagesInQuoteChainSync(application, messageId))
    }
  }

  @WorkerThread
  private fun getMessagesInQuoteChainSync(application: Application, messageId: MessageId): List<ConversationMessage> {
    val rootMessageId: MessageId = GenZappDatabase.messages.getRootOfQuoteChain(messageId)

    var originalRecord: MessageRecord? = GenZappDatabase.messages.getMessageRecordOrNull(rootMessageId.id)

    if (originalRecord == null) {
      return emptyList()
    }

    var replyRecords: List<MessageRecord> = GenZappDatabase.messages.getAllMessagesThatQuote(rootMessageId)

    val reactionHelper = ReactionHelper()
    val attachmentHelper = AttachmentHelper()
    val threadRecipient = requireNotNull(GenZappDatabase.threads.getRecipientForThreadId(originalRecord.threadId))

    reactionHelper.addAll(replyRecords)
    attachmentHelper.addAll(replyRecords)

    reactionHelper.fetchReactions()
    attachmentHelper.fetchAttachments()

    replyRecords = reactionHelper.buildUpdatedModels(replyRecords)
    replyRecords = attachmentHelper.buildUpdatedModels(AppDependencies.application, replyRecords)

    val replies: List<ConversationMessage> = replyRecords
      .map { replyRecord ->
        val replyQuote: Quote? = replyRecord.getQuote()
        if (replyQuote != null && replyQuote.id == originalRecord!!.dateSent) {
          (replyRecord as MmsMessageRecord).withoutQuote()
        } else {
          replyRecord
        }
      }
      .map { ConversationMessageFactory.createWithUnresolvedData(application, it, threadRecipient) }

    if (originalRecord.isPaymentNotification) {
      originalRecord = GenZappDatabase.payments.updateMessageWithPayment(originalRecord)
    }

    originalRecord = ReactionHelper()
      .apply {
        add(originalRecord)
        fetchReactions()
      }
      .buildUpdatedModels(listOf(originalRecord))
      .get(0)

    originalRecord = AttachmentHelper()
      .apply {
        add(originalRecord)
        fetchAttachments()
      }
      .buildUpdatedModels(AppDependencies.application, listOf(originalRecord))
      .get(0)

    val originalMessage: ConversationMessage = ConversationMessageFactory.createWithUnresolvedData(application, originalRecord, false, threadRecipient)

    return replies + originalMessage
  }
}
