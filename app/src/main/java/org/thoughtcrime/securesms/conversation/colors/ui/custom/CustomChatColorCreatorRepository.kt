package org.thoughtcrime.securesms.conversation.colors.ui.custom

import android.content.Context
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper

class CustomChatColorCreatorRepository(private val context: Context) {
  fun loadColors(chatColorsId: ChatColors.Id, consumer: (ChatColors) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val chatColors = GenZappDatabase.chatColors.getById(chatColorsId)
      consumer(chatColors)
    }
  }

  fun getWallpaper(recipientId: RecipientId?, consumer: (ChatWallpaper?) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      if (recipientId != null) {
        val recipient = Recipient.resolved(recipientId)
        consumer(recipient.wallpaper)
      } else {
        consumer(GenZappStore.wallpaper.wallpaper)
      }
    }
  }

  fun setChatColors(chatColors: ChatColors, consumer: (ChatColors) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val savedColors = GenZappDatabase.chatColors.saveChatColors(chatColors)
      consumer(savedColors)
    }
  }

  fun getUsageCount(chatColorsId: ChatColors.Id, consumer: (Int) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val recipientsDatabase = GenZappDatabase.recipients

      consumer(recipientsDatabase.getColorUsageCount(chatColorsId))
    }
  }
}
