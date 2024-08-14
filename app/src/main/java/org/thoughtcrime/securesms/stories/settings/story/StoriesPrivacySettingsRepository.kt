package org.thoughtcrime.securesms.stories.settings.story

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import org.thoughtcrime.securesms.stories.Stories

class StoriesPrivacySettingsRepository {
  fun markGroupsAsStories(groups: List<RecipientId>): Completable {
    return Completable.fromCallable {
      GenZappDatabase.groups.setShowAsStoryState(groups, GroupTable.ShowAsStoryState.ALWAYS)
      GenZappDatabase.recipients.markNeedsSync(groups)
      StorageSyncHelper.scheduleSyncForDataChange()
    }
  }

  fun setStoriesEnabled(isEnabled: Boolean): Completable {
    return Completable.fromAction {
      GenZappStore.story.isFeatureDisabled = !isEnabled
      Stories.onStorySettingsChanged(Recipient.self().id)
      AppDependencies.resetNetwork()

      GenZappDatabase.messages.getAllOutgoingStories(false, -1).use { reader ->
        reader.map { record -> record.id }
      }.forEach { messageId ->
        MessageSender.sendRemoteDelete(messageId)
      }
    }.subscribeOn(Schedulers.io())
  }

  fun onSettingsChanged() {
    GenZappExecutors.BOUNDED_IO.execute {
      Stories.onStorySettingsChanged(Recipient.self().id)
    }
  }

  fun userHasOutgoingStories(): Single<Boolean> {
    return Single.fromCallable {
      GenZappDatabase.messages.getAllOutgoingStories(false, -1).use {
        it.iterator().hasNext()
      }
    }.subscribeOn(Schedulers.io())
  }
}
