package org.thoughtcrime.securesms.components.settings.app.internal

import android.content.Context
import org.json.JSONObject
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.GenZapp.donations.InAppPaymentType
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.RemoteMegaphoneRecord
import org.thoughtcrime.securesms.database.model.addStyle
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.emoji.EmojiFiles
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob
import org.thoughtcrime.securesms.jobs.CreateReleaseChannelJob
import org.thoughtcrime.securesms.jobs.FetchRemoteMegaphoneImageJob
import org.thoughtcrime.securesms.jobs.InAppPaymentRecurringContextJob
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.releasechannel.ReleaseChannel
import java.util.UUID
import kotlin.time.Duration.Companion.days

class InternalSettingsRepository(context: Context) {

  private val context = context.applicationContext

  fun getEmojiVersionInfo(consumer: (EmojiFiles.Version?) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      consumer(EmojiFiles.Version.readVersion(context))
    }
  }

  fun enqueueSubscriptionRedemption() {
    GenZappExecutors.BOUNDED.execute {
      val latest = GenZappDatabase.inAppPayments.getByLatestEndOfPeriod(InAppPaymentType.RECURRING_DONATION)
      if (latest != null) {
        InAppPaymentRecurringContextJob.createJobChain(latest).enqueue()
      }
    }
  }

  fun addSampleReleaseNote() {
    GenZappExecutors.UNBOUNDED.execute {
      AppDependencies.jobManager.runSynchronously(CreateReleaseChannelJob.create(), 5000)

      val title = "Release Note Title"
      val bodyText = "Release note body. Aren't I awesome?"
      val body = "$title\n\n$bodyText"
      val bodyRangeList = BodyRangeList.Builder()
        .addStyle(BodyRangeList.BodyRange.Style.BOLD, 0, title.length)

      val recipientId = GenZappStore.releaseChannel.releaseChannelRecipientId!!
      val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipientId))

      val insertResult: MessageTable.InsertResult? = ReleaseChannel.insertReleaseChannelMessage(
        recipientId = recipientId,
        body = body,
        threadId = threadId,
        messageRanges = bodyRangeList.build(),
        media = "/static/release-notes/GenZapp.png",
        mediaWidth = 1800,
        mediaHeight = 720
      )

      GenZappDatabase.messages.insertBoostRequestMessage(recipientId, threadId)

      if (insertResult != null) {
        GenZappDatabase.attachments.getAttachmentsForMessage(insertResult.messageId)
          .forEach { AppDependencies.jobManager.add(AttachmentDownloadJob(insertResult.messageId, it.attachmentId, false)) }

        AppDependencies.messageNotifier.updateNotification(context, ConversationId.forConversation(insertResult.threadId))
      }
    }
  }

  fun addRemoteMegaphone(actionId: RemoteMegaphoneRecord.ActionId) {
    GenZappExecutors.UNBOUNDED.execute {
      val record = RemoteMegaphoneRecord(
        uuid = UUID.randomUUID().toString(),
        priority = 100,
        countries = "*:1000000",
        minimumVersion = 1,
        doNotShowBefore = System.currentTimeMillis() - 2.days.inWholeMilliseconds,
        doNotShowAfter = System.currentTimeMillis() + 28.days.inWholeMilliseconds,
        showForNumberOfDays = 30,
        conditionalId = null,
        primaryActionId = actionId,
        secondaryActionId = RemoteMegaphoneRecord.ActionId.SNOOZE,
        imageUrl = "/static/release-notes/donate-heart.png",
        title = "Donate Test",
        body = "Donate body test.",
        primaryActionText = "Donate",
        secondaryActionText = "Snooze",
        primaryActionData = null,
        secondaryActionData = JSONObject("{ \"snoozeDurationDays\": [5, 7, 100] }")
      )

      GenZappDatabase.remoteMegaphones.insert(record)

      if (record.imageUrl != null) {
        AppDependencies.jobManager.add(FetchRemoteMegaphoneImageJob(record.uuid, record.imageUrl))
      }
    }
  }
}
