package org.thoughtcrime.securesms.conversation.colors.ui

import android.content.Context
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper

sealed class ChatColorSelectionRepository(context: Context) {

  protected val context: Context = context.applicationContext

  abstract fun getWallpaper(consumer: (ChatWallpaper?) -> Unit)
  abstract fun getChatColors(consumer: (ChatColors) -> Unit)
  abstract fun save(chatColors: ChatColors, onSaved: () -> Unit)

  fun duplicate(chatColors: ChatColors) {
    GenZappExecutors.BOUNDED.execute {
      val duplicate = chatColors.withId(ChatColors.Id.NotSet)
      GenZappDatabase.chatColors.saveChatColors(duplicate)
    }
  }

  fun getUsageCount(chatColorsId: ChatColors.Id, consumer: (Int) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      consumer(GenZappDatabase.recipients.getColorUsageCount(chatColorsId))
    }
  }

  fun delete(chatColors: ChatColors, onDeleted: () -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      GenZappDatabase.chatColors.deleteChatColors(chatColors)
      onDeleted()
    }
  }

  private class Global(context: Context) : ChatColorSelectionRepository(context) {
    override fun getWallpaper(consumer: (ChatWallpaper?) -> Unit) {
      consumer(GenZappStore.wallpaper.wallpaper)
    }

    override fun getChatColors(consumer: (ChatColors) -> Unit) {
      if (GenZappStore.chatColors.hasChatColors) {
        consumer(requireNotNull(GenZappStore.chatColors.chatColors))
      } else {
        getWallpaper { wallpaper ->
          if (wallpaper != null) {
            consumer(wallpaper.autoChatColors)
          } else {
            consumer(ChatColorsPalette.Bubbles.default.withId(ChatColors.Id.Auto))
          }
        }
      }
    }

    override fun save(chatColors: ChatColors, onSaved: () -> Unit) {
      if (chatColors.id == ChatColors.Id.Auto) {
        GenZappStore.chatColors.chatColors = null
      } else {
        GenZappStore.chatColors.chatColors = chatColors
      }
      onSaved()
    }
  }

  private class Single(context: Context, private val recipientId: RecipientId) : ChatColorSelectionRepository(context) {
    override fun getWallpaper(consumer: (ChatWallpaper?) -> Unit) {
      GenZappExecutors.BOUNDED.execute {
        val recipient = Recipient.resolved(recipientId)
        consumer(recipient.wallpaper)
      }
    }

    override fun getChatColors(consumer: (ChatColors) -> Unit) {
      GenZappExecutors.BOUNDED.execute {
        val recipient = Recipient.resolved(recipientId)
        consumer(recipient.chatColors)
      }
    }

    override fun save(chatColors: ChatColors, onSaved: () -> Unit) {
      GenZappExecutors.BOUNDED.execute {
        val recipientDatabase = GenZappDatabase.recipients
        recipientDatabase.setColor(recipientId, chatColors)
        onSaved()
      }
    }
  }

  companion object {
    fun create(context: Context, recipientId: RecipientId?): ChatColorSelectionRepository {
      return if (recipientId != null) {
        Single(context, recipientId)
      } else {
        Global(context)
      }
    }
  }
}
