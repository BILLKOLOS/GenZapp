package org.thoughtcrime.securesms.stories.viewer

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Open for testing
 */
open class StoryViewerRepository {
  fun getFirstStory(recipientId: RecipientId, storyId: Long): Single<MmsMessageRecord> {
    return if (storyId > 0) {
      Single.fromCallable {
        GenZappDatabase.messages.getMessageRecord(storyId) as MmsMessageRecord
      }
    } else {
      Single.fromCallable {
        val recipient = Recipient.resolved(recipientId)
        val reader: MessageTable.Reader = if (recipient.isMyStory || recipient.isSelf) {
          GenZappDatabase.messages.getAllOutgoingStories(false, 1)
        } else {
          val unread = GenZappDatabase.messages.getUnreadStories(recipientId, 1)
          if (unread.iterator().hasNext()) {
            unread
          } else {
            GenZappDatabase.messages.getAllStoriesFor(recipientId, 1)
          }
        }
        reader.use { it.iterator().next() } as MmsMessageRecord
      }
    }
  }

  fun getStories(hiddenStories: Boolean, isOutgoingOnly: Boolean): Single<List<RecipientId>> {
    return Single.create { emitter ->
      val myStoriesId = GenZappDatabase.recipients.getOrInsertFromDistributionListId(DistributionListId.MY_STORY)
      val myStories = Recipient.resolved(myStoriesId)
      val releaseChannelId = GenZappStore.releaseChannel.releaseChannelRecipientId
      val recipientIds = GenZappDatabase.messages.getOrderedStoryRecipientsAndIds(isOutgoingOnly).groupBy {
        val recipient = Recipient.resolved(it.recipientId)
        if (recipient.isDistributionList) {
          myStories
        } else {
          recipient
        }
      }.keys.filter {
        if (hiddenStories) {
          it.shouldHideStory
        } else {
          !it.shouldHideStory
        }
      }.map { it.id }

      emitter.onSuccess(
        recipientIds.floatToTop(releaseChannelId).floatToTop(myStoriesId)
      )
    }.subscribeOn(Schedulers.io())
  }

  private fun List<RecipientId>.floatToTop(recipientId: RecipientId?): List<RecipientId> {
    return if (recipientId != null && contains(recipientId)) {
      listOf(recipientId) + (this - recipientId)
    } else {
      this
    }
  }
}
