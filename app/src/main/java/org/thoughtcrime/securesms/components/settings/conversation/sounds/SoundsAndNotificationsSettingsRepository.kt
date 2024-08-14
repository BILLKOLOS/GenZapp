package org.thoughtcrime.securesms.components.settings.conversation.sounds

import android.content.Context
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

class SoundsAndNotificationsSettingsRepository(private val context: Context) {

  fun ensureCustomChannelConsistency(complete: () -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      if (NotificationChannels.supported()) {
        NotificationChannels.getInstance().ensureCustomChannelConsistency()
      }
      complete()
    }
  }

  fun setMuteUntil(recipientId: RecipientId, muteUntil: Long) {
    GenZappExecutors.BOUNDED.execute {
      GenZappDatabase.recipients.setMuted(recipientId, muteUntil)
    }
  }

  fun setMentionSetting(recipientId: RecipientId, mentionSetting: RecipientTable.MentionSetting) {
    GenZappExecutors.BOUNDED.execute {
      GenZappDatabase.recipients.setMentionSetting(recipientId, mentionSetting)
    }
  }

  fun hasCustomNotificationSettings(recipientId: RecipientId, consumer: (Boolean) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val recipient = Recipient.resolved(recipientId)
      consumer(
        if (recipient.notificationChannel != null || !NotificationChannels.supported()) {
          true
        } else {
          NotificationChannels.getInstance().updateWithShortcutBasedChannel(recipient)
        }
      )
    }
  }
}
