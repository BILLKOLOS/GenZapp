package org.thoughtcrime.securesms.components.settings.app.privacy.expire

import android.content.Context
import androidx.annotation.WorkerThread
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.groups.GroupChangeException
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import java.io.IOException

private val TAG: String = Log.tag(ExpireTimerSettingsRepository::class.java)

/**
 * Provide operations to set expire timer for individuals and groups.
 */
class ExpireTimerSettingsRepository(val context: Context) {

  fun setExpiration(recipientId: RecipientId, newExpirationTime: Int, consumer: (Result<Int>) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val recipient = Recipient.resolved(recipientId)
      if (recipient.groupId.isPresent && recipient.groupId.get().isPush) {
        try {
          GroupManager.updateGroupTimer(context, recipient.groupId.get().requirePush(), newExpirationTime)
          consumer.invoke(Result.success(newExpirationTime))
        } catch (e: GroupChangeException) {
          Log.w(TAG, e)
          consumer.invoke(Result.failure(e))
        } catch (e: IOException) {
          Log.w(TAG, e)
          consumer.invoke(Result.failure(e))
        }
      } else {
        GenZappDatabase.recipients.setExpireMessages(recipientId, newExpirationTime)
        val outgoingMessage = OutgoingMessage.expirationUpdateMessage(Recipient.resolved(recipientId), System.currentTimeMillis(), newExpirationTime * 1000L)
        MessageSender.send(context, outgoingMessage, getThreadId(recipientId), MessageSender.SendType.GenZapp, null, null)
        consumer.invoke(Result.success(newExpirationTime))
      }
    }
  }

  fun setUniversalExpireTimerSeconds(newExpirationTime: Int, onDone: () -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      GenZappStore.settings.universalExpireTimer = newExpirationTime
      GenZappDatabase.recipients.markNeedsSync(Recipient.self().id)
      StorageSyncHelper.scheduleSyncForDataChange()
      onDone.invoke()
    }
  }

  @WorkerThread
  private fun getThreadId(recipientId: RecipientId): Long {
    val threadTable: ThreadTable = GenZappDatabase.threads
    val recipient: Recipient = Recipient.resolved(recipientId)
    return threadTable.getOrCreateThreadIdFor(recipient)
  }
}
