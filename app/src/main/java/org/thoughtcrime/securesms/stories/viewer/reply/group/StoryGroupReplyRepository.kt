package org.thoughtcrime.securesms.stories.viewer.reply.group

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.GenZapp.paging.ObservablePagedData
import org.GenZapp.paging.PagedData
import org.GenZapp.paging.PagingConfig
import org.GenZapp.paging.PagingController
import org.thoughtcrime.securesms.conversation.colors.GroupAuthorNameColorHelper
import org.thoughtcrime.securesms.conversation.colors.NameColor
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.NoSuchMessageException
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.RecipientId

class StoryGroupReplyRepository {

  fun getThreadId(storyId: Long): Single<Long> {
    return Single.fromCallable {
      GenZappDatabase.messages.getThreadIdForMessage(storyId)
    }.subscribeOn(Schedulers.io())
  }

  fun getPagedReplies(parentStoryId: Long): Observable<ObservablePagedData<MessageId, ReplyBody>> {
    return getThreadId(parentStoryId)
      .toObservable()
      .flatMap { threadId ->
        Observable.create<ObservablePagedData<MessageId, ReplyBody>> { emitter ->
          val pagedData: ObservablePagedData<MessageId, ReplyBody> = PagedData.createForObservable(StoryGroupReplyDataSource(parentStoryId), PagingConfig.Builder().build())
          val controller: PagingController<MessageId> = pagedData.controller

          val updateObserver = DatabaseObserver.MessageObserver { controller.onDataItemChanged(it) }
          val insertObserver = DatabaseObserver.MessageObserver { controller.onDataItemInserted(it, PagingController.POSITION_END) }
          val conversationObserver = DatabaseObserver.Observer { controller.onDataInvalidated() }

          AppDependencies.databaseObserver.registerMessageUpdateObserver(updateObserver)
          AppDependencies.databaseObserver.registerMessageInsertObserver(threadId, insertObserver)
          AppDependencies.databaseObserver.registerConversationObserver(threadId, conversationObserver)

          emitter.setCancellable {
            AppDependencies.databaseObserver.unregisterObserver(updateObserver)
            AppDependencies.databaseObserver.unregisterObserver(insertObserver)
            AppDependencies.databaseObserver.unregisterObserver(conversationObserver)
          }

          emitter.onNext(pagedData)
        }.subscribeOn(Schedulers.io())
      }
  }

  fun getNameColorsMap(storyId: Long): Observable<Map<RecipientId, NameColor>> {
    return Single
      .fromCallable {
        try {
          val messageRecord = GenZappDatabase.messages.getMessageRecord(storyId)
          val groupId = messageRecord.toRecipient.groupId.or { messageRecord.fromRecipient.groupId }
          if (groupId.isPresent) {
            GroupAuthorNameColorHelper().getColorMap(groupId.get())
          } else {
            emptyMap()
          }
        } catch (e: NoSuchMessageException) {
          emptyMap()
        }
      }
      .toObservable()
  }
}
