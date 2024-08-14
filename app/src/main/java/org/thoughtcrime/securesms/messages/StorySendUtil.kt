package org.thoughtcrime.securesms.messages

import org.GenZapp.core.util.Base64
import org.thoughtcrime.securesms.database.model.databaseprotos.StoryTextPost
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.whispersystems.GenZappservice.api.messages.GenZappServicePreview
import org.whispersystems.GenZappservice.api.messages.GenZappServiceTextAttachment
import java.io.IOException
import java.util.Optional
import kotlin.math.roundToInt

object StorySendUtil {
  @JvmStatic
  @Throws(IOException::class)
  fun deserializeBodyToStoryTextAttachment(message: OutgoingMessage, getPreviewsFor: (OutgoingMessage) -> List<GenZappServicePreview>): GenZappServiceTextAttachment {
    val storyTextPost = StoryTextPost.ADAPTER.decode(Base64.decode(message.body))
    val preview = if (message.linkPreviews.isEmpty()) {
      Optional.empty()
    } else {
      Optional.of(getPreviewsFor(message)[0])
    }

    return if (storyTextPost.background!!.linearGradient != null) {
      GenZappServiceTextAttachment.forGradientBackground(
        Optional.ofNullable(storyTextPost.body),
        Optional.ofNullable(getStyle(storyTextPost.style)),
        Optional.of(storyTextPost.textForegroundColor),
        Optional.of(storyTextPost.textBackgroundColor),
        preview,
        GenZappServiceTextAttachment.Gradient(
          Optional.of(storyTextPost.background.linearGradient!!.rotation.roundToInt()),
          ArrayList(storyTextPost.background.linearGradient.colors),
          ArrayList(storyTextPost.background.linearGradient.positions)
        )
      )
    } else {
      GenZappServiceTextAttachment.forSolidBackground(
        Optional.ofNullable(storyTextPost.body),
        Optional.ofNullable(getStyle(storyTextPost.style)),
        Optional.of(storyTextPost.textForegroundColor),
        Optional.of(storyTextPost.textBackgroundColor),
        preview,
        storyTextPost.background.singleColor!!.color
      )
    }
  }

  private fun getStyle(style: StoryTextPost.Style): GenZappServiceTextAttachment.Style {
    return when (style) {
      StoryTextPost.Style.REGULAR -> GenZappServiceTextAttachment.Style.REGULAR
      StoryTextPost.Style.BOLD -> GenZappServiceTextAttachment.Style.BOLD
      StoryTextPost.Style.SERIF -> GenZappServiceTextAttachment.Style.SERIF
      StoryTextPost.Style.SCRIPT -> GenZappServiceTextAttachment.Style.SCRIPT
      StoryTextPost.Style.CONDENSED -> GenZappServiceTextAttachment.Style.CONDENSED
      else -> GenZappServiceTextAttachment.Style.DEFAULT
    }
  }
}
