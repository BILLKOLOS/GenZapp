package org.thoughtcrime.securesms.stories.settings.custom

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.database.model.DistributionListRecord
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.stories.Stories

class PrivateStorySettingsRepository {
  fun getRecord(distributionListId: DistributionListId): Single<DistributionListRecord> {
    return Single.fromCallable {
      GenZappDatabase.distributionLists.getList(distributionListId) ?: error("Record does not exist.")
    }.subscribeOn(Schedulers.io())
  }

  fun removeMember(distributionListRecord: DistributionListRecord, member: RecipientId): Completable {
    return Completable.fromAction {
      GenZappDatabase.distributionLists.removeMemberFromList(distributionListRecord.id, distributionListRecord.privacyMode, member)
      Stories.onStorySettingsChanged(distributionListRecord.id)
    }.subscribeOn(Schedulers.io())
  }

  fun delete(distributionListId: DistributionListId): Completable {
    return Completable.fromAction {
      GenZappDatabase.distributionLists.deleteList(distributionListId)
      Stories.onStorySettingsChanged(distributionListId)

      val recipientId = GenZappDatabase.recipients.getOrInsertFromDistributionListId(distributionListId)
      GenZappDatabase.messages.getAllStoriesFor(recipientId, -1).use { reader ->
        for (record in reader) {
          MessageSender.sendRemoteDelete(record.id)
        }
      }
    }.subscribeOn(Schedulers.io())
  }

  fun getRepliesAndReactionsEnabled(distributionListId: DistributionListId): Single<Boolean> {
    return Single.fromCallable {
      GenZappDatabase.distributionLists.getStoryType(distributionListId).isStoryWithReplies
    }.subscribeOn(Schedulers.io())
  }

  fun setRepliesAndReactionsEnabled(distributionListId: DistributionListId, repliesAndReactionsEnabled: Boolean): Completable {
    return Completable.fromAction {
      GenZappDatabase.distributionLists.setAllowsReplies(distributionListId, repliesAndReactionsEnabled)
      Stories.onStorySettingsChanged(distributionListId)
    }.subscribeOn(Schedulers.io())
  }
}
