package org.thoughtcrime.securesms.stories.viewer.views

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.GenZappservice.api.push.DistributionId

class StoryViewsRepository {

  companion object {
    private val TAG = Log.tag(StoryViewsRepository::class.java)
  }

  fun isReadReceiptsEnabled(): Boolean = GenZappStore.story.viewedReceiptsEnabled

  fun getStoryRecipient(storyId: Long): Single<Recipient> {
    return Single.fromCallable {
      GenZappDatabase.messages.getMessageRecord(storyId).toRecipient
    }.subscribeOn(Schedulers.io())
  }

  fun getViews(storyId: Long): Observable<List<StoryViewItemData>> {
    return Observable.create<List<StoryViewItemData>> { emitter ->
      val record: MessageRecord = GenZappDatabase.messages.getMessageRecord(storyId)
      val filterIds: Set<RecipientId> = if (record.toRecipient.isDistributionList) {
        val distributionId: DistributionId = GenZappDatabase.distributionLists.getDistributionId(record.toRecipient.requireDistributionListId())!!
        GenZappDatabase.storySends.getRecipientsForDistributionId(storyId, distributionId)
      } else {
        emptySet()
      }

      fun refresh() {
        emitter.onNext(
          GenZappDatabase.groupReceipts.getGroupReceiptInfo(storyId).filter {
            it.status == GroupReceiptTable.STATUS_VIEWED
          }.filter {
            filterIds.isEmpty() || it.recipientId in filterIds
          }.map {
            StoryViewItemData(
              recipient = Recipient.resolved(it.recipientId),
              timeViewedInMillis = it.timestamp
            )
          }
        )
      }

      val observer = DatabaseObserver.MessageObserver { refresh() }

      AppDependencies.databaseObserver.registerMessageUpdateObserver(observer)
      emitter.setCancellable {
        AppDependencies.databaseObserver.unregisterObserver(observer)
      }

      refresh()
    }.subscribeOn(Schedulers.io())
  }

  fun removeUserFromStory(user: Recipient, story: Recipient): Completable {
    return Completable.fromAction {
      val distributionListRecord = GenZappDatabase.distributionLists.getList(story.requireDistributionListId())!!
      if (user.id in distributionListRecord.members) {
        GenZappDatabase.distributionLists.excludeFromStory(user.id, distributionListRecord)
      } else {
        Log.w(TAG, "User is no longer in the distribution list.")
      }
    }
  }
}
