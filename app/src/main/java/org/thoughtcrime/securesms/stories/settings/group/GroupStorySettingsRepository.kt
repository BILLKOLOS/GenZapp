package org.thoughtcrime.securesms.stories.settings.group

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.storage.StorageSyncHelper

class GroupStorySettingsRepository {
  fun unmarkAsGroupStory(groupId: GroupId): Completable {
    return Completable.fromAction {
      GenZappDatabase.groups.setShowAsStoryState(groupId, GroupTable.ShowAsStoryState.NEVER)
      GenZappDatabase.recipients.markNeedsSync(Recipient.externalGroupExact(groupId).id)
      StorageSyncHelper.scheduleSyncForDataChange()
    }.subscribeOn(Schedulers.io())
  }

  fun getConversationData(groupId: GroupId): Single<GroupConversationData> {
    return Single.fromCallable {
      val recipientId = GenZappDatabase.recipients.getByGroupId(groupId).get()
      val threadId = GenZappDatabase.threads.getThreadIdFor(recipientId) ?: -1L

      GroupConversationData(recipientId, threadId)
    }.subscribeOn(Schedulers.io())
  }
}
