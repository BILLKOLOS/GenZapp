package org.thoughtcrime.securesms.conversation.ui.edit

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.v2.data.AttachmentHelper
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.notifications.MarkReadReceiver
import org.thoughtcrime.securesms.recipients.Recipient

object EditMessageHistoryRepository {

  fun getEditHistory(messageId: Long): Observable<List<ConversationMessage>> {
    return Observable.create { emitter ->
      val threadId: Long = GenZappDatabase.messages.getThreadIdForMessage(messageId)
      if (threadId < 0) {
        emitter.onNext(emptyList())
        return@create
      }

      val databaseObserver: DatabaseObserver = AppDependencies.databaseObserver
      val observer = DatabaseObserver.Observer { emitter.onNext(getEditHistorySync(messageId)) }

      databaseObserver.registerConversationObserver(threadId, observer)

      emitter.setCancellable { databaseObserver.unregisterObserver(observer) }
      emitter.onNext(getEditHistorySync(messageId))
    }.subscribeOn(Schedulers.io())
  }

  fun markRevisionsRead(messageId: Long) {
    GenZappExecutors.BOUNDED.execute {
      MarkReadReceiver.process(GenZappDatabase.messages.setAllEditMessageRevisionsRead(messageId))
    }
  }

  private fun getEditHistorySync(messageId: Long): List<ConversationMessage> {
    val context = AppDependencies.application
    val records = GenZappDatabase
      .messages
      .getMessageEditHistory(messageId)
      .toList()
      .reversed()

    if (records.isEmpty()) {
      return emptyList()
    }

    val attachmentHelper = AttachmentHelper()
      .apply {
        addAll(records)
        fetchAttachments()
      }

    val threadRecipient: Recipient = requireNotNull(GenZappDatabase.threads.getRecipientForThreadId(records[0].threadId))

    return attachmentHelper
      .buildUpdatedModels(context, records)
      .map { ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(context, it, threadRecipient) }
  }
}
