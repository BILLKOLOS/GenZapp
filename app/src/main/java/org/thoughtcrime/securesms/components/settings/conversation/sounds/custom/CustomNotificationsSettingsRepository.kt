package org.thoughtcrime.securesms.components.settings.conversation.sounds.custom

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.concurrent.SerialExecutor

class CustomNotificationsSettingsRepository(context: Context) {

  private val context = context.applicationContext
  private val executor = SerialExecutor(GenZappExecutors.BOUNDED)

  fun ensureCustomChannelConsistency(recipientId: RecipientId, onComplete: () -> Unit) {
    executor.execute {
      if (NotificationChannels.supported()) {
        NotificationChannels.getInstance().ensureCustomChannelConsistency()

        val recipient = Recipient.resolved(recipientId)
        val database = GenZappDatabase.recipients
        if (recipient.notificationChannel != null) {
          val ringtoneUri: Uri? = NotificationChannels.getInstance().getMessageRingtone(recipient)
          database.setMessageRingtone(recipient.id, if (ringtoneUri == Uri.EMPTY) null else ringtoneUri)
          database.setMessageVibrate(recipient.id, RecipientTable.VibrateState.fromBoolean(NotificationChannels.getInstance().getMessageVibrate(recipient)))
        }
      }

      onComplete()
    }
  }

  fun setHasCustomNotifications(recipientId: RecipientId, hasCustomNotifications: Boolean) {
    executor.execute {
      if (hasCustomNotifications) {
        createCustomNotificationChannel(recipientId)
      } else {
        deleteCustomNotificationChannel(recipientId)
      }
    }
  }

  fun setMessageVibrate(recipientId: RecipientId, vibrateState: RecipientTable.VibrateState) {
    executor.execute {
      val recipient: Recipient = Recipient.resolved(recipientId)

      GenZappDatabase.recipients.setMessageVibrate(recipient.id, vibrateState)
      NotificationChannels.getInstance().updateMessageVibrate(recipient, vibrateState)
    }
  }

  fun setCallingVibrate(recipientId: RecipientId, vibrateState: RecipientTable.VibrateState) {
    executor.execute {
      GenZappDatabase.recipients.setCallVibrate(recipientId, vibrateState)
    }
  }

  fun setMessageSound(recipientId: RecipientId, sound: Uri?) {
    executor.execute {
      val recipient: Recipient = Recipient.resolved(recipientId)
      val defaultValue = GenZappStore.settings.messageNotificationSound
      val newValue: Uri? = if (defaultValue == sound) null else sound ?: Uri.EMPTY

      GenZappDatabase.recipients.setMessageRingtone(recipient.id, newValue)
      NotificationChannels.getInstance().updateMessageRingtone(recipient, newValue)
    }
  }

  fun setCallSound(recipientId: RecipientId, sound: Uri?) {
    executor.execute {
      val defaultValue = GenZappStore.settings.callRingtone
      val newValue: Uri? = if (defaultValue == sound) null else sound ?: Uri.EMPTY

      GenZappDatabase.recipients.setCallRingtone(recipientId, newValue)
    }
  }

  @WorkerThread
  private fun createCustomNotificationChannel(recipientId: RecipientId) {
    val recipient: Recipient = Recipient.resolved(recipientId)
    val channelId = NotificationChannels.getInstance().createChannelFor(recipient)
    GenZappDatabase.recipients.setNotificationChannel(recipient.id, channelId)
  }

  @WorkerThread
  private fun deleteCustomNotificationChannel(recipientId: RecipientId) {
    val recipient: Recipient = Recipient.resolved(recipientId)
    GenZappDatabase.recipients.setNotificationChannel(recipient.id, null)
    NotificationChannels.getInstance().deleteChannelFor(recipient)
  }
}
